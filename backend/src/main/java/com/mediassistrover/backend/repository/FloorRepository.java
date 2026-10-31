package com.mediassistrover.backend.repository;

import com.mediassistrover.backend.entity.Floor;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface FloorRepository extends JpaRepository<Floor, UUID> {
    boolean existsByHospitalInformationHospitalInformationIdAndFloorCodeIgnoreCase(UUID hospitalId, String floorCode);
    Page<Floor> findByActiveTrue(Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Floor f where f.floorId = :id")
    Optional<Floor> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            select f from Floor f where f.active = true
              and f.hospitalInformation.status = com.mediassistrover.backend.enums.HospitalInformationStatus.PUBLISHED
              and f.hospitalInformation.publicVisible = true
              and (f.hospitalInformation.effectiveFrom is null or f.hospitalInformation.effectiveFrom <= :now)
              and (f.hospitalInformation.effectiveUntil is null or f.hospitalInformation.effectiveUntil > :now)
              and (:hospitalId is null or f.hospitalInformation.hospitalInformationId = :hospitalId)
              and (:q is null or lower(f.floorName) like lower(concat('%', :q, '%'))
                   or lower(f.floorCode) like lower(concat('%', :q, '%')))
            """)
    Page<Floor> searchPublic(@Param("hospitalId") UUID hospitalId, @Param("q") String q,
                             @Param("now") java.time.Instant now, Pageable pageable);

    @Query("""
            select f from Floor f where f.floorId = :id and f.active = true
              and f.hospitalInformation.status = com.mediassistrover.backend.enums.HospitalInformationStatus.PUBLISHED
              and f.hospitalInformation.publicVisible = true
              and (f.hospitalInformation.effectiveFrom is null or f.hospitalInformation.effectiveFrom <= :now)
              and (f.hospitalInformation.effectiveUntil is null or f.hospitalInformation.effectiveUntil > :now)
            """)
    Optional<Floor> findPublicById(@Param("id") UUID id, @Param("now") java.time.Instant now);
}
