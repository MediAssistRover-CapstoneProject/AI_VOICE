package com.mediassistrover.backend.repository;

import com.mediassistrover.backend.entity.RobotOperationAuthorization;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface RobotOperationAuthorizationRepository extends JpaRepository<RobotOperationAuthorization,UUID> {
    Optional<RobotOperationAuthorization> findByJobStepJobStepId(UUID id);
    Optional<RobotOperationAuthorization> findByVerificationVerificationId(UUID id);
    List<RobotOperationAuthorization> findByVerificationJobJobId(UUID id);
}
