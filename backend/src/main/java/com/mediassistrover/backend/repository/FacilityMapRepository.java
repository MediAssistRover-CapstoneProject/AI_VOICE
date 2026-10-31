package com.mediassistrover.backend.repository;

import com.mediassistrover.backend.entity.FacilityMap;
import com.mediassistrover.backend.enums.MapStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FacilityMapRepository extends JpaRepository<FacilityMap, UUID> {
    boolean existsByMapCodeIgnoreCaseAndVersionNo(String mapCode, int versionNo);
    Optional<FacilityMap> findByFloorFloorIdAndStatus(UUID floorId, MapStatus status);
    List<FacilityMap> findAllByFloorFloorIdAndStatus(UUID floorId, MapStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from FacilityMap m where m.floor.floorId = :floorId and m.status = :status")
    List<FacilityMap> findAllByFloorAndStatusForUpdate(@Param("floorId") UUID floorId,
                                                       @Param("status") MapStatus status);
    Page<FacilityMap> findByStatus(MapStatus status, Pageable pageable);
}
