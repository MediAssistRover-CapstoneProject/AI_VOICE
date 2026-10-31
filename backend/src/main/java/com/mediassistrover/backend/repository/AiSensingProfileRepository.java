package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.AiSensingProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface AiSensingProfileRepository extends JpaRepository<AiSensingProfile,UUID> {
    Optional<AiSensingProfile> findByRobotIdAndZoneIdAndSensingType(UUID robotId,UUID zoneId,String sensingType);
}
