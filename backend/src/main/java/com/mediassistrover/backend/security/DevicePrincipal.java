package com.mediassistrover.backend.security;

import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DevicePrincipal(UUID robotId, String robotCode, long tokenVersion,
                              String jti, Instant expiresAt) {
    public List<SimpleGrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_DEVICE"));
    }
}
