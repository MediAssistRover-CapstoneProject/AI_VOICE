package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.RobotNavigationProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface RobotNavigationProfileRepository extends JpaRepository<RobotNavigationProfile,UUID> { }
