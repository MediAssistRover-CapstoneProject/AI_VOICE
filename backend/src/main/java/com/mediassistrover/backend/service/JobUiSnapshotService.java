package com.mediassistrover.backend.service;

import com.mediassistrover.backend.dto.job.response.JobResponses.JobDetailResponse;
import com.mediassistrover.backend.enums.AccountStatus;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.AccountPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.UUID;

@Service @RequiredArgsConstructor
public class JobUiSnapshotService {
    private final JobService jobs;
    private final AccountRepository accounts;
    private final RevokedTokenRepository revoked;
    private final Clock clock;
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW)
    public JobDetailResponse snapshot(UUID jobId,AccountPrincipal actor) {
        var account=accounts.findById(actor.accountId()).orElseThrow();
        Instant now=Instant.now(clock);
        if (account.getStatus()!=AccountStatus.ACTIVE || account.isMustChangePassword() || account.getRole()!=actor.role()
                || account.getTokenVersion()!=actor.tokenVersion() || (account.getLockedUntil()!=null && account.getLockedUntil().isAfter(now))
                || (actor.tokenExpiresAt()!=null && !actor.tokenExpiresAt().isAfter(now))
                || (actor.jti()!=null && revoked.existsByJti(actor.jti())))
            throw new AccessDeniedException("Session is no longer valid");
        return jobs.get(jobId,actor); // Rechecks assignment/ownership for each pushed update.
    }
}
