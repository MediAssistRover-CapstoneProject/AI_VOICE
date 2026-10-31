package com.mediassistrover.backend.security;

import com.mediassistrover.backend.enums.AccountStatus;
import com.mediassistrover.backend.repository.AccountRepository;
import com.mediassistrover.backend.repository.RevokedTokenRepository;
import com.mediassistrover.backend.security.RestAccessDeniedHandler;
import org.springframework.security.access.AccessDeniedException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenService jwtTokenService;
    private final AccountRepository accountRepository;
    private final RevokedTokenRepository revokedTokenRepository;
    private final RestAccessDeniedHandler accessDeniedHandler;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(authorization.substring(7));
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AccountPrincipal principal
                && accountRepository.findById(principal.accountId()).map(a -> a.isMustChangePassword()).orElse(false)
                && !("PUT".equals(request.getMethod()) && "/api/v1/auth/change-password".equals(request.getRequestURI().substring(request.getContextPath().length())))) {
            accessDeniedHandler.handle(request, response, new AccessDeniedException("Password change is required"));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(String token) {
        try {
            JwtClaims claims = jwtTokenService.parse(token);
            if (!JwtTokenService.USER_TOKEN_TYPE.equals(claims.tokenType())
                    || revokedTokenRepository.existsByJti(claims.jti())) {
                return;
            }
            accountRepository.findById(claims.accountId())
                    .filter(account -> account.getStatus() == AccountStatus.ACTIVE)
                    .filter(account -> account.getTokenVersion() == claims.tokenVersion())
                    .ifPresent(account -> {
                        AccountPrincipal principal = AccountPrincipal.fromAccount(
                                account, claims.jti(), claims.expiresAt());
                        if (principal.isAccountNonLocked()) {
                            var authentication = new UsernamePasswordAuthenticationToken(
                                    principal, null, principal.getAuthorities());
                            SecurityContextHolder.getContext().setAuthentication(authentication);
                        }
                    });
        } catch (RuntimeException ignored) {
            SecurityContextHolder.clearContext();
        }
    }
}
