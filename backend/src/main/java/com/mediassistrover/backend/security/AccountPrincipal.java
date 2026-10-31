package com.mediassistrover.backend.security;

import com.mediassistrover.backend.entity.Account;
import com.mediassistrover.backend.enums.AccountStatus;
import com.mediassistrover.backend.enums.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public record AccountPrincipal(
        UUID accountId,
        String username,
        String password,
        Role role,
        AccountStatus status,
        long tokenVersion,
        String jti,
        Instant tokenExpiresAt,
        Instant lockedUntil
) implements UserDetails {

    public static AccountPrincipal fromAccount(Account account) {
        return fromAccount(account, null, null);
    }

    public static AccountPrincipal fromAccount(Account account, String jti, Instant tokenExpiresAt) {
        return new AccountPrincipal(
                account.getAccountId(),
                account.getUsername(),
                account.getPasswordHash(),
                account.getRole(),
                account.getStatus(),
                account.getTokenVersion(),
                jti,
                tokenExpiresAt,
                account.getLockedUntil()
        );
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return status != AccountStatus.LOCKED && (lockedUntil == null || !lockedUntil.isAfter(Instant.now()));
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return status == AccountStatus.ACTIVE;
    }
}
