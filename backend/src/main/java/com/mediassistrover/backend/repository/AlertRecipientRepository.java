package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.AlertRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface AlertRecipientRepository extends JpaRepository<AlertRecipient,UUID> {
    List<AlertRecipient> findByAlertAlertIdOrderByCreatedAtAsc(UUID alertId);
    Optional<AlertRecipient> findByAlertAlertIdAndAccountAccountId(UUID alertId,UUID accountId);
    boolean existsByAlertAlertIdAndAccountAccountId(UUID alertId, UUID accountId);
    long countByAlertAlertId(UUID alertId);
    long countByAlertAlertIdAndAcknowledgedAtIsNotNull(UUID alertId);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from AlertRecipient r where r.alert.alertId=:alertId and r.account.accountId=:accountId") Optional<AlertRecipient> lockRecipient(@Param("alertId") UUID alertId,@Param("accountId") UUID accountId);
    long countByAlertAlertIdAndDeliveryStatus(UUID alertId,com.mediassistrover.backend.enums.AlertDeliveryStatus status);
}
