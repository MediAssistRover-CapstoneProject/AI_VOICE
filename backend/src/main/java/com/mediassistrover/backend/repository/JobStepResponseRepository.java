package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.StaffStepResponseRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface JobStepResponseRepository extends JpaRepository<StaffStepResponseRecord,UUID> {
    List<StaffStepResponseRecord> findByJobStepJobStepId(UUID stepId);
    // Current read after the Job lock: REPEATABLE READ must see a concurrent winner's committed receipt.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<StaffStepResponseRecord> findByJobStepJobStepIdAndIdempotencyKey(UUID stepId,String key);
}
