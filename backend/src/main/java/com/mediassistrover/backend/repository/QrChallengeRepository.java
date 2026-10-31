package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.QrChallenge;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface QrChallengeRepository extends JpaRepository<QrChallenge,UUID> {
    Optional<QrChallenge> findByAuthJobStepJobStepId(UUID stepId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from QrChallenge q where q.tokenHash=:hash")
    Optional<QrChallenge> lockByTokenHash(@Param("hash") String hash);
}
