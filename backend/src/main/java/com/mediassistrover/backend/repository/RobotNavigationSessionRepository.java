package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.RobotNavigationSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface RobotNavigationSessionRepository extends JpaRepository<RobotNavigationSession,UUID> { }
