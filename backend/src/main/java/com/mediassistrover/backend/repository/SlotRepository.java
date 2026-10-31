package com.mediassistrover.backend.repository;

import com.mediassistrover.backend.entity.Slot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SlotRepository extends JpaRepository<Slot, UUID> {
    Optional<Slot> findBySlotIdAndShiftShiftId(UUID slotId, UUID shiftId);
    boolean existsByShiftShiftIdAndSequenceNo(UUID shiftId, int sequenceNo);

    @Query("select count(s) > 0 from Slot s where s.shift.shiftId = :shiftId " +
            "and (:excludeId is null or s.slotId <> :excludeId) and s.startsAt < :endsAt and s.endsAt > :startsAt")
    boolean overlaps(@Param("shiftId") UUID shiftId, @Param("startsAt") Instant startsAt,
                     @Param("endsAt") Instant endsAt, @Param("excludeId") UUID excludeId);
}
