package com.mediassistrover.backend.repository;

import com.mediassistrover.backend.entity.Shift;
import com.mediassistrover.backend.enums.ShiftStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShiftRepository extends JpaRepository<Shift, UUID> {
    boolean existsByShiftCodeIgnoreCase(String shiftCode);

    @EntityGraph(attributePaths = {"staffProfile", "room", "slots"})
    @Query("select distinct s from Shift s where s.shiftId = :id")
    Optional<Shift> findDetailedById(@Param("id") UUID id);

    @Query("select s from Shift s where (:staffId is null or s.staffProfile.staffProfileId = :staffId) " +
            "and (:roomId is null or s.room.roomId = :roomId) and (:status is null or s.status = :status)")
    Page<Shift> search(@Param("staffId") UUID staffId, @Param("roomId") UUID roomId,
                       @Param("status") ShiftStatus status, Pageable pageable);

    @Query("select s from Shift s where s.staffProfile.account.accountId = :accountId " +
            "and (:status is null or s.status = :status)")
    Page<Shift> findOwn(@Param("accountId") UUID accountId, @Param("status") ShiftStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"staffProfile", "staffProfile.account", "room", "slots"})
    @Query("select distinct s from Shift s where s.staffProfile.account.accountId = :accountId " +
            "and s.status = com.mediassistrover.backend.enums.ShiftStatus.ACTIVE " +
            "and s.startsAt <= :now and s.endsAt > :now order by s.startsAt, s.shiftCode")
    List<Shift> findEffectiveActiveByAccountId(@Param("accountId") UUID accountId, @Param("now") Instant now);

    @Query("select count(s) > 0 from Shift s where s.staffProfile.account.accountId = :accountId " +
            "and s.staffProfile.active = true " +
            "and s.staffProfile.account.status = com.mediassistrover.backend.enums.AccountStatus.ACTIVE " +
            "and s.room.roomId = :roomId " +
            "and s.status = com.mediassistrover.backend.enums.ShiftStatus.ACTIVE " +
            "and s.startsAt <= :now and s.endsAt > :now")
    boolean existsEffectiveActiveInRoom(@Param("accountId") UUID accountId,
                                        @Param("roomId") UUID roomId,
                                        @Param("now") Instant now);

    @Query("select count(s) > 0 from Shift s where s.staffProfile.staffProfileId = :staffId " +
            "and s.status <> com.mediassistrover.backend.enums.ShiftStatus.CANCELLED " +
            "and (:excludeId is null or s.shiftId <> :excludeId) and s.startsAt < :endsAt and s.endsAt > :startsAt")
    boolean overlaps(@Param("staffId") UUID staffId, @Param("startsAt") Instant startsAt,
                     @Param("endsAt") Instant endsAt, @Param("excludeId") UUID excludeId);
}
