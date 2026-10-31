package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.RobotMqttSession;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface RobotMqttSessionRepository extends JpaRepository<RobotMqttSession,UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from RobotMqttSession s where s.robotId=:id")
    Optional<RobotMqttSession> lock(@Param("id") UUID id);
}
