package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.AuthVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface AuthVerificationRepository extends JpaRepository<AuthVerification,UUID> {
    Optional<AuthVerification> findByAuthJobStepJobStepId(UUID jobStepId);
    List<AuthVerification> findByJobJobIdOrderByIssuedAtAsc(UUID jobId);
}
