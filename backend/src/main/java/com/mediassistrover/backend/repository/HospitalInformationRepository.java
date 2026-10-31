package com.mediassistrover.backend.repository;

import com.mediassistrover.backend.entity.HospitalInformation;
import com.mediassistrover.backend.enums.HospitalInformationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface HospitalInformationRepository extends JpaRepository<HospitalInformation, UUID> {
    @Query("""
            select h from HospitalInformation h
            where h.status = :status and h.publicVisible = true
              and (h.effectiveFrom is null or h.effectiveFrom <= :now)
              and (h.effectiveUntil is null or h.effectiveUntil > :now)
            """)
    Page<HospitalInformation> findPublicEffective(@Param("status") HospitalInformationStatus status,
                                                   @Param("now") Instant now, Pageable pageable);

    @Query("""
            select h from HospitalInformation h
            where h.status = :status and h.publicVisible = true
              and (h.effectiveFrom is null or h.effectiveFrom <= :now)
              and (h.effectiveUntil is null or h.effectiveUntil > :now)
              and (:category is null or lower(h.infoCategory) = lower(:category))
              and (:languageCode is null or lower(h.languageCode) = lower(:languageCode))
              and (:q is null or lower(h.title) like lower(concat('%', :q, '%'))
                   or lower(h.content) like lower(concat('%', :q, '%')))
            """)
    Page<HospitalInformation> searchPublic(@Param("status") HospitalInformationStatus status,
            @Param("now") Instant now, @Param("q") String q, @Param("category") String category,
            @Param("languageCode") String languageCode, Pageable pageable);

    @Query("""
            select h from HospitalInformation h where h.hospitalInformationId = :id
              and h.status = :status and h.publicVisible = true
              and (h.effectiveFrom is null or h.effectiveFrom <= :now)
              and (h.effectiveUntil is null or h.effectiveUntil > :now)
            """)
    java.util.Optional<HospitalInformation> findPublicById(@Param("id") UUID id,
            @Param("status") HospitalInformationStatus status, @Param("now") Instant now);
}
