package com.mediassistrover.backend.controller.auth;

import com.mediassistrover.backend.dto.account.response.AccountResponse;
import com.mediassistrover.backend.dto.auth.request.ChangePasswordRequest;
import com.mediassistrover.backend.dto.auth.request.LoginRequest;
import com.mediassistrover.backend.dto.auth.request.LogoutRequest;
import com.mediassistrover.backend.dto.auth.request.RefreshTokenRequest;
import com.mediassistrover.backend.dto.auth.response.TokenResponse;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.AuthService;
import com.mediassistrover.backend.util.RequestMetadataFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    @Operation(summary = "Đăng nhập tài khoản đã ACTIVE", security = {})
    TokenResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        return authService.login(body, RequestMetadataFactory.from(request));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate refresh token", security = {})
    TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest body, HttpServletRequest request) {
        return authService.refresh(body, RequestMetadataFactory.from(request));
    }

    @PostMapping("/logout")
    @Operation(summary = "Đăng xuất idempotent")
    @SecurityRequirement(name = "bearerAuth")
    ResponseEntity<Void> logout(
            @AuthenticationPrincipal AccountPrincipal principal,
            @Valid @RequestBody LogoutRequest body,
            HttpServletRequest request
    ) {
        if (principal != null) {
            authService.logout(principal, body, RequestMetadataFactory.from(request));
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    @Operation(summary = "Lấy Account hiện tại")
    AccountResponse me(@AuthenticationPrincipal AccountPrincipal principal) {
        return authService.currentUser(principal);
    }

    @PutMapping("/change-password")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    @Operation(summary = "Đổi mật khẩu và thu hồi toàn bộ phiên cũ")
    ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal AccountPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest body,
            HttpServletRequest request
    ) {
        authService.changePassword(principal, body, RequestMetadataFactory.from(request));
        return ResponseEntity.noContent().build();
    }
}
