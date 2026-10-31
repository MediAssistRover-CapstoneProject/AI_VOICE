package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.MqttOperationRecoveryReceipt;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.util.*;
public interface MqttOperationRecoveryReceiptRepository extends JpaRepository<MqttOperationRecoveryReceipt,UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MqttOperationRecoveryReceipt> findByJobStepIdAndIdempotencyKey(UUID step,String key);
}
