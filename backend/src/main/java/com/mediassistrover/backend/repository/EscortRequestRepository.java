package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.EscortRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface EscortRequestRepository extends JpaRepository<EscortRequest,UUID> {}
