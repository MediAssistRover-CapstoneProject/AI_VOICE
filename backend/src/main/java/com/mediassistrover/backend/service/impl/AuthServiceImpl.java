package com.mediassistrover.backend.service.impl;

import com.mediassistrover.backend.config.AuthProperties;
import com.mediassistrover.backend.dto.account.response.AccountResponse;
import com.mediassistrover.backend.dto.auth.request.ChangePasswordRequest;
import com.mediassistrover.backend.dto.auth.request.LoginRequest;
import com.mediassistrover.backend.dto.auth.request.LogoutRequest;
import com.mediassistrover.backend.dto.auth.request.RefreshTokenRequest;
import com.mediassistrover.backend.dto.auth.response.TokenResponse;
import com.mediassistrover.backend.entity.Account;
import com.mediassistrover.backend.enums.AccountStatus;
import com.mediassistrover.backend.enums.AuthProvider;
import com.mediassistrover.backend.enums.RevocationReason;
import com.mediassistrover.backend.enums.Role;
import com.mediassistrover.backend.exception.AuthenticationFailedException;
import com.mediassistrover.backend.exception.ConflictException;
import com.mediassistrover.backend.exception.ResourceNotFoundException;
import com.mediassistrover.backend.mapper.AccountMapper;
import com.mediassistrover.backend.repository.AccountRepository;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.security.JwtTokenService;
import com.mediassistrover.backend.service.AuditService;
import com.mediassistrover.backend.service.AuthService;
import com.mediassistrover.backend.service.RefreshTokenService;
import com.mediassistrover.backend.service.RequestMetadata;
import com.mediassistrover.backend.service.TokenRevocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenService refreshTokenService;
    private final TokenRevocationService tokenRevocationService;
    private final AuditService auditService;
    private final AccountMapper accountMapper;
    private final AuthProperties authProperties;
    private final Clock clock;

    @Override
    @Transactional(noRollbackFor = AuthenticationFailedException.class)
    public TokenResponse login(LoginRequest request, RequestMetadata metadata) {
        Account account = accountRepository.findByUsernameIgnoreCase(normalizeUsername(request.username()))
                .orElseThrow(() -> new AuthenticationFailedException("Tên đăng nhập hoặc mật khẩu không đúng"));
        Instant now = Instant.now(clock);
        if (account.getLockedUntil() != null && account.getLockedUntil().isAfter(now)) {
            throw new AuthenticationFailedException("Tài khoản đang bị khóa tạm thời");
        }
        if (account.getLockedUntil() != null) {
            account.setLockedUntil(null);
            account.setFailedLoginAttempts(0);
        }
        if (!passwordEncoder.matches(request.password(), account.getPasswordHash())) {
            recordFailedLogin(account, now);
            throw new AuthenticationFailedException("Tên đăng nhập hoặc mật khẩu không đúng");
        }
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AuthenticationFailedException("Tài khoản chưa được phép đăng nhập");
        }

        account.setFailedLoginAttempts(0);
        account.setLockedUntil(null);
        account.setLastLoginAt(now);
        JwtTokenService.IssuedAccessToken access = jwtTokenService.issueAccessToken(account);
        RefreshTokenService.IssuedRefreshToken refresh = refreshTokenService.issue(
                account, request.deviceInfo(), metadata.ipAddress());
        auditService.record(account, "LOGIN_SUCCEEDED", "Account", account.getAccountId(), null,
                Map.of("jti", access.jti()), metadata);
        return tokenResponse(access, refresh, account.isMustChangePassword());
    }

    @Override
    @Transactional(noRollbackFor = com.mediassistrover.backend.exception.InvalidTokenException.class)
    public TokenResponse refresh(RefreshTokenRequest request, RequestMetadata metadata) {
        RefreshTokenService.RotationResult rotation = refreshTokenService.rotate(
                request.refreshToken(), request.deviceInfo(), metadata.ipAddress());
        JwtTokenService.IssuedAccessToken access = jwtTokenService.issueAccessToken(rotation.account());
        auditService.record(rotation.account(), "TOKEN_REFRESHED", "Account", rotation.account().getAccountId(),
                null, Map.of("jti", access.jti()), metadata);
        return tokenResponse(access, rotation.token(), rotation.account().isMustChangePassword());
    }

    @Override
    @Transactional
    public void logout(AccountPrincipal principal, LogoutRequest request, RequestMetadata metadata) {
        Account account = requireAccount(principal.accountId());
        tokenRevocationService.revokeAccessToken(principal.jti(), account, principal.tokenExpiresAt(),
                RevocationReason.LOGOUT);
        refreshTokenService.revoke(request.refreshToken(), account);
        auditService.record(account, "LOGOUT", "Account", account.getAccountId(), null, null, metadata);
    }

    @Override
    @Transactional(readOnly = true)
    public AccountResponse currentUser(AccountPrincipal principal) {
        return accountMapper.toResponse(requireAccount(principal.accountId()));
    }

    @Override
    @Transactional
    public void changePassword(AccountPrincipal principal, ChangePasswordRequest request, RequestMetadata metadata) {
        Account account = requireAccount(principal.accountId());
        if (!passwordEncoder.matches(request.currentPassword(), account.getPasswordHash())) {
            throw new AuthenticationFailedException("Mật khẩu hiện tại không đúng");
        }
        if (passwordEncoder.matches(request.newPassword(), account.getPasswordHash())) {
            throw new ConflictException("Mật khẩu mới phải khác mật khẩu hiện tại");
        }
        account.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        account.setPasswordChangedAt(Instant.now(clock));
        account.setMustChangePassword(false);
        account.setTokenVersion(account.getTokenVersion() + 1);
        refreshTokenService.revokeAll(account, RevocationReason.PASSWORD_CHANGED);
        tokenRevocationService.revokeAccessToken(principal.jti(), account, principal.tokenExpiresAt(),
                RevocationReason.PASSWORD_CHANGED);
        auditService.record(account, "PASSWORD_CHANGED", "Account", account.getAccountId(), null, null, metadata);
    }

    private void recordFailedLogin(Account account, Instant now) {
        int attempts = account.getFailedLoginAttempts() + 1;
        account.setFailedLoginAttempts(attempts);
        if (attempts >= authProperties.maxFailedLoginAttempts()) {
            account.setLockedUntil(now.plus(authProperties.temporaryLockMinutes(), ChronoUnit.MINUTES));
            account.setFailedLoginAttempts(0);
        }
        accountRepository.save(account);
    }

    private void validateUnique(String username, String email, String phone) {
        if (accountRepository.existsByUsernameIgnoreCase(username)) {
            throw new ConflictException("Username đã tồn tại");
        }
        if (accountRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("Email đã tồn tại");
        }
        if (accountRepository.existsByPhone(phone)) {
            throw new ConflictException("Số điện thoại đã tồn tại");
        }
    }

    private Account requireAccount(UUID id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy Account"));
    }

    private String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizePhone(String phone) {
        return phone.trim().replace(" ", "").replace("-", "").replace(".", "");
    }

    private TokenResponse tokenResponse(
            JwtTokenService.IssuedAccessToken access,
            RefreshTokenService.IssuedRefreshToken refresh, boolean mustChangePassword
    ) {
        return new TokenResponse(access.value(), refresh.value(), "Bearer", access.expiresAt(), refresh.expiresAt(), mustChangePassword);
    }
}
