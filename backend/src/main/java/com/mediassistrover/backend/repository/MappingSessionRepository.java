package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.MappingSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface MappingSessionRepository extends JpaRepository<MappingSession,UUID> {
    Optional<MappingSession> findByJobId(UUID jobId);
    List<MappingSession> findByRobotIdAndPotentiallyOpenTrue(UUID robotId);
}
