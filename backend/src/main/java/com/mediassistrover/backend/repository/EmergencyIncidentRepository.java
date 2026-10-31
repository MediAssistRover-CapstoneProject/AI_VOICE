package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.EmergencyIncident;
import com.mediassistrover.backend.enums.EmergencyIncidentStatus;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
public interface EmergencyIncidentRepository extends JpaRepository<EmergencyIncident,UUID> {
    Page<EmergencyIncident> findByStatus(EmergencyIncidentStatus status,Pageable pageable);
    Optional<EmergencyIncident> findByDetectionEventDetectionEventId(UUID detectionEventId);
    long countByStatusIn(Collection<EmergencyIncidentStatus> statuses);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from EmergencyIncident i where i.incidentId=:id")
    Optional<EmergencyIncident> lockById(@Param("id") UUID id);
    @Query("select i from EmergencyIncident i where (:status is null or i.status=:status) " +
            "and (:severity is null or i.severity=:severity) and (:type is null or lower(i.incidentType)=lower(:type)) " +
            "and (:mapId is null or i.map.mapId=:mapId) and (:roomId is null or i.endpoint.room.roomId=:roomId) " +
            "and (:from is null or i.occurredAt>=:from) and (:to is null or i.occurredAt<:to)")
    Page<EmergencyIncident> search(@Param("status") EmergencyIncidentStatus status,
            @Param("severity") com.mediassistrover.backend.enums.EmergencyIncidentSeverity severity,
            @Param("type") String type,@Param("mapId") UUID mapId,@Param("roomId") UUID roomId,
            @Param("from") Instant from,@Param("to") Instant to,Pageable pageable);
}
