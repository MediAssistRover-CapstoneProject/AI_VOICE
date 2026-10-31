package com.mediassistrover.backend.repository;

import com.mediassistrover.backend.entity.Room;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface RoomRepository extends JpaRepository<Room, UUID> {
    boolean existsByRoomCodeIgnoreCase(String roomCode);
    Page<Room> findByActiveTrueAndPublicVisibleTrue(Pageable pageable);
    @Query("""
            select r from Room r where r.active = true and r.publicVisible = true
              and (:floorId is null or r.floor.floorId = :floorId)
              and (:roomType is null or lower(r.roomType) = lower(:roomType))
              and (:departmentCode is null or lower(r.departmentCode) = lower(:departmentCode))
              and (:q is null or lower(r.roomName) like lower(concat('%', :q, '%'))
                   or lower(r.roomCode) like lower(concat('%', :q, '%')))
            """)
    Page<Room> searchPublic(@Param("floorId") UUID floorId, @Param("roomType") String roomType,
            @Param("departmentCode") String departmentCode, @Param("q") String q, Pageable pageable);
    java.util.Optional<Room> findByRoomIdAndActiveTrueAndPublicVisibleTrue(UUID id);
}
