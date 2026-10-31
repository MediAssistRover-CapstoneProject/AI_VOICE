package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.NavigationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface NavigationRequestRepository extends JpaRepository<NavigationRequest,UUID> {}
