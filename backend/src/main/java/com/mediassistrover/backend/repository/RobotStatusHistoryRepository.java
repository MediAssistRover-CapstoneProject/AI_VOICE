package com.mediassistrover.backend.repository;

import com.mediassistrover.backend.entity.RobotStatusHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RobotStatusHistoryRepository extends JpaRepository<RobotStatusHistory, UUID> {
    Page<RobotStatusHistory> findByRobotRobotId(UUID robotId, Pageable pageable);
}
