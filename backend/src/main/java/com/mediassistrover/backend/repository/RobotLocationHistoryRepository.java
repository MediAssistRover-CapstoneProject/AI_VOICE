package com.mediassistrover.backend.repository;

import com.mediassistrover.backend.entity.RobotLocationHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface RobotLocationHistoryRepository extends JpaRepository<RobotLocationHistory, UUID> {
    java.util.Optional<RobotLocationHistory> findFirstByRobotRobotIdOrderByCapturedAtDesc(UUID robotId);
    java.util.Optional<com.mediassistrover.backend.entity.RobotLocationHistory> findFirstByRobotRobotIdAndMapMapIdAndFreshTrueOrderByCapturedAtDesc(UUID robot,UUID map);
    Page<RobotLocationHistory> findByRobotRobotId(UUID robotId, Pageable pageable);
    @Query("select h from RobotLocationHistory h where h.robot.robotId=:robotId " +
            "and (:from is null or h.capturedAt>=:from) and (:to is null or h.capturedAt<:to)")
    Page<RobotLocationHistory> search(@Param("robotId") UUID robotId,@Param("from") Instant from,
                                      @Param("to") Instant to,Pageable pageable);
}
