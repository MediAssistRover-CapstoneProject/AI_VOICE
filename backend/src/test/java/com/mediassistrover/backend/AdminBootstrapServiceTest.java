package com.mediassistrover.backend;

import com.mediassistrover.backend.config.AdminBootstrapProperties;
import com.mediassistrover.backend.entity.Account;
import com.mediassistrover.backend.enums.AccountStatus;
import com.mediassistrover.backend.enums.Role;
import com.mediassistrover.backend.repository.AccountRepository;
import com.mediassistrover.backend.service.impl.AdminBootstrapService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ApplicationArguments applicationArguments;

    @Test
    void createsInitialAdminWithoutPersistingPlaintextPassword() {
        AdminBootstrapProperties properties = new AdminBootstrapProperties(
                "RootAdmin", "StrongPassword!123", "ADMIN@EXAMPLE.COM", "ADM-001", "System Admin");
        Clock clock = Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"), ZoneOffset.UTC);
        AdminBootstrapService service = new AdminBootstrapService(
                properties, accountRepository, passwordEncoder, clock);

        when(accountRepository.findByUsernameIgnoreCase("rootadmin")).thenReturn(Optional.empty());
        when(accountRepository.existsByAccountCode("ADM-001")).thenReturn(false);
        when(accountRepository.existsByEmailIgnoreCase("ADMIN@EXAMPLE.COM")).thenReturn(false);
        when(passwordEncoder.encode("StrongPassword!123")).thenReturn("bcrypt-hash");

        service.run(applicationArguments);

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        Account admin = captor.getValue();
        assertThat(admin.getUsername()).isEqualTo("rootadmin");
        assertThat(admin.getEmail()).isEqualTo("admin@example.com");
        assertThat(admin.getPasswordHash()).isEqualTo("bcrypt-hash");
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(admin.isMustChangePassword()).isFalse();
    }

    @Test
    void skipsBootstrapWhenAdminUsernameAlreadyExists() {
        AdminBootstrapProperties properties = new AdminBootstrapProperties(
                "rootadmin", "StrongPassword!123", "admin@example.com", "ADM-001", "System Admin");
        AdminBootstrapService service = new AdminBootstrapService(
                properties, accountRepository, passwordEncoder, Clock.systemUTC());

        when(accountRepository.findByUsernameIgnoreCase("rootadmin"))
                .thenReturn(Optional.of(Account.builder().username("rootadmin").build()));

        service.run(applicationArguments);

        verify(accountRepository, never()).save(any(Account.class));
        verify(passwordEncoder, never()).encode(any());
    }
}
