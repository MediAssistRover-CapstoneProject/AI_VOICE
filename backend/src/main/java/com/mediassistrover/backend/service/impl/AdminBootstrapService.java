package com.mediassistrover.backend.service.impl;

import com.mediassistrover.backend.config.AdminBootstrapProperties;
import com.mediassistrover.backend.entity.Account;
import com.mediassistrover.backend.enums.AccountStatus;
import com.mediassistrover.backend.enums.AuthProvider;
import com.mediassistrover.backend.enums.Role;
import com.mediassistrover.backend.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class AdminBootstrapService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapService.class);
    private final AdminBootstrapProperties properties;
    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (properties.isEmpty()) {
            log.info("Admin bootstrap is not configured; skipping initial Admin creation");
            return;
        }
        if (!properties.isComplete()) {
            throw new IllegalStateException("Admin bootstrap variables must be either all empty or all provided");
        }
        String username = properties.adminUsername().trim().toLowerCase(Locale.ROOT);
        if (accountRepository.findByUsernameIgnoreCase(username).isPresent()) {
            log.info("Initial Admin already exists; bootstrap is idempotently skipped");
            return;
        }
        if (accountRepository.existsByAccountCode(properties.adminAccountCode().trim())) {
            throw new IllegalStateException("ADMIN_ACCOUNT_CODE is already used");
        }
        if (accountRepository.existsByEmailIgnoreCase(properties.adminEmail().trim())) {
            throw new IllegalStateException("ADMIN_EMAIL is already used");
        }
        Instant now = Instant.now(clock);
        accountRepository.save(Account.builder()
                .accountCode(properties.adminAccountCode().trim())
                .username(username)
                .fullName(properties.adminFullName().trim())
                .email(properties.adminEmail().trim().toLowerCase(Locale.ROOT))
                .role(Role.ADMIN)
                .authProvider(AuthProvider.LOCAL)
                .passwordHash(passwordEncoder.encode(properties.adminPassword()))
                .status(AccountStatus.ACTIVE)
                .passwordChangedAt(now)
                .build());
        log.info("Initial Admin account was created successfully");
    }
}
