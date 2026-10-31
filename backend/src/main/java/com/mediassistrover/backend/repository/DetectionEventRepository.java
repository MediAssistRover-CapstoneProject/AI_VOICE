package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.DetectionEvent;
import com.mediassistrover.backend.enums.DetectionEventType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.UUID;
public interface DetectionEventRepository extends JpaRepository<DetectionEvent,UUID> {
    @Query("select d from DetectionEvent d where (:robotId is null or d.robot.robotId=:robotId) and (:type is null or d.eventType=:type) and (:from is null or d.detectedAt>=:from) and (:to is null or d.detectedAt<:to)")
    Page<DetectionEvent> search(@Param("robotId") UUID robotId,@Param("type") DetectionEventType type,@Param("from") Instant from,@Param("to") Instant to,Pageable pageable);
}
