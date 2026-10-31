package com.mediassistrover.backend.service;

import com.mediassistrover.backend.dto.account.response.AccountResponse;
import com.mediassistrover.backend.dto.auth.request.ChangePasswordRequest;
import com.mediassistrover.backend.dto.auth.request.LoginRequest;
import com.mediassistrover.backend.dto.auth.request.LogoutRequest;
import com.mediassistrover.backend.dto.auth.request.RefreshTokenRequest;
import com.mediassistrover.backend.dto.auth.response.TokenResponse;
import com.mediassistrover.backend.security.AccountPrincipal;

public interface AuthService {
    TokenResponse login(LoginRequest request, RequestMetadata metadata);
    TokenResponse refresh(RefreshTokenRequest request, RequestMetadata metadata);
    void logout(AccountPrincipal principal, LogoutRequest request, RequestMetadata metadata);
    AccountResponse currentUser(AccountPrincipal principal);
    void changePassword(AccountPrincipal principal, ChangePasswordRequest request, RequestMetadata metadata);
}
