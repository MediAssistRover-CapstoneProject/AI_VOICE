package com.mediassistrover.backend.repository;

import com.mediassistrover.backend.entity.VoiceInteraction;
import com.mediassistrover.backend.enums.VoiceInteractionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface VoiceInteractionRepository extends JpaRepository<VoiceInteraction, UUID> {
    @Query("select v from VoiceInteraction v where (:robotId is null or v.robot.robotId=:robotId) " +
            "and (:status is null or v.status=:status) and (:from is null or v.startedAt>=:from) " +
            "and (:to is null or v.startedAt<:to)")
    Page<VoiceInteraction> search(@Param("robotId") UUID robotId,
                                  @Param("status") VoiceInteractionStatus status,
                                  @Param("from") Instant from,
                                  @Param("to") Instant to,
                                  Pageable pageable);
}
