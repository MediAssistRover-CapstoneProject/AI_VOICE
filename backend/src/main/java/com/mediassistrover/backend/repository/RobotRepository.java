package com.mediassistrover.backend.repository;

import com.mediassistrover.backend.entity.Robot;
import com.mediassistrover.backend.enums.RobotStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RobotRepository extends JpaRepository<Robot, UUID> {
    interface StatusCount { RobotStatus getStatus(); long getTotal(); }
    Optional<Robot> findByRobotCodeIgnoreCase(String robotCode);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Robot r where r.robotId = :robotId")
    Optional<Robot> findByIdForUpdate(@Param("robotId") UUID robotId);
    boolean existsByRobotCodeIgnoreCase(String robotCode);
    boolean existsBySerialNumber(String serialNumber);
    Page<Robot> findByStatus(RobotStatus status, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Robot> findByStatusNotAndLastHeartbeatAtLessThanEqual(RobotStatus status, Instant threshold);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Robot> findByStatusOrderByRobotCodeAsc(RobotStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Robot> findByStatusAndCurrentMapMapIdOrderByRobotCodeAsc(RobotStatus status,UUID mapId);
    @Query("select r.status as status, count(r) as total from Robot r group by r.status")
    List<StatusCount> countGroupedByStatus();
}
