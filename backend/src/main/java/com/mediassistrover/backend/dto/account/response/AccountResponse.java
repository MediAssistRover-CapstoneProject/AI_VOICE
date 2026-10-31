package com.mediassistrover.backend.dto.account.response;

import com.mediassistrover.backend.enums.AccountStatus;
import com.mediassistrover.backend.enums.AuthProvider;
import com.mediassistrover.backend.enums.Role;

import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID accountId,
        String accountCode,
        String username,
        String fullName,
        String email,
        String phone,
        Role role,
        AuthProvider authProvider,
        AccountStatus status,
        Instant lastLoginAt,
        boolean mustChangePassword,
        Instant createdAt,
        Instant updatedAt
) {
}
