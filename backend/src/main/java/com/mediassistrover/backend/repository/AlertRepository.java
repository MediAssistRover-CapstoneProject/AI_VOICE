package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.Alert;
import com.mediassistrover.backend.enums.AlertStatus;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;
import java.time.Instant;
public interface AlertRepository extends JpaRepository<Alert,UUID> {
    @Query("select a.jobStep.jobTask.job.jobId from Alert a where a.alertId=:id") Optional<UUID> findJobId(@Param("id") UUID id);
    Optional<Alert> findByOperationKey(String operationKey);
    Page<Alert> findByStatus(AlertStatus status,Pageable pageable);
    List<Alert> findByIncidentIncidentId(UUID incidentId);
    long countByStatusIn(Collection<AlertStatus> statuses);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select a from Alert a where a.alertId=:id") Optional<Alert> lockById(@Param("id") UUID id);
    @Query("select distinct a from Alert a join AlertRecipient r on r.alert=a where r.account.accountId=:accountId and (:status is null or a.status=:status)")
    Page<Alert> findForRecipient(@Param("accountId") UUID accountId,@Param("status") AlertStatus status,Pageable pageable);
    @Query("select a from Alert a where (:status is null or a.status=:status) and (:severity is null or a.severity=:severity) " +
            "and (:type is null or lower(a.notificationType)=lower(:type)) and (:from is null or a.createdAt>=:from) " +
            "and (:to is null or a.createdAt<:to)")
    Page<Alert> search(@Param("status") AlertStatus status,
                       @Param("severity") com.mediassistrover.backend.enums.AlertSeverity severity,
                       @Param("type") String type,@Param("from") Instant from,@Param("to") Instant to,
                       Pageable pageable);
}
