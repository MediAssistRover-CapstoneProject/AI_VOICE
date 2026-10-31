package com.mediassistrover.backend.repository;

import com.mediassistrover.backend.entity.HospitalInformationArticle;
import com.mediassistrover.backend.enums.HospitalInformationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface HospitalInformationArticleRepository extends JpaRepository<HospitalInformationArticle, UUID> {
    boolean existsByHospitalInformationHospitalInformationIdAndArticleCodeIgnoreCaseAndLanguageCodeIgnoreCase(
            UUID hospitalId, String articleCode, String languageCode);

    @Query("""
            select a from HospitalInformationArticle a
            where a.hospitalInformation.hospitalInformationId = :hospitalId
              and (:status is null or a.status = :status)
            """)
    Page<HospitalInformationArticle> search(@Param("hospitalId") UUID hospitalId,
                                            @Param("status") HospitalInformationStatus status,
                                            Pageable pageable);

    @Query("""
            select a from HospitalInformationArticle a
            where a.status = :status and a.publicVisible = true
              and a.hospitalInformation.status = :status and a.hospitalInformation.publicVisible = true
              and (a.hospitalInformation.effectiveFrom is null or a.hospitalInformation.effectiveFrom <= :now)
              and (a.hospitalInformation.effectiveUntil is null or a.hospitalInformation.effectiveUntil > :now)
              and lower(a.languageCode) = lower(:languageCode)
              and (a.effectiveFrom is null or a.effectiveFrom <= :now)
              and (a.effectiveUntil is null or a.effectiveUntil >= :now)
            order by a.updatedAt desc
            """)
    List<HospitalInformationArticle> findEffectiveCandidates(@Param("status") HospitalInformationStatus status,
                                                              @Param("languageCode") String languageCode,
                                                              @Param("now") Instant now,
                                                              Pageable pageable);

    @Query("""
            select a from HospitalInformationArticle a
            where a.hospitalInformation.hospitalInformationId = :hospitalId
              and a.hospitalInformation.status = :status and a.hospitalInformation.publicVisible = true
              and (a.hospitalInformation.effectiveFrom is null or a.hospitalInformation.effectiveFrom <= :now)
              and (a.hospitalInformation.effectiveUntil is null or a.hospitalInformation.effectiveUntil > :now)
              and a.status = :status and a.publicVisible = true
              and (a.effectiveFrom is null or a.effectiveFrom <= :now)
              and (a.effectiveUntil is null or a.effectiveUntil > :now)
              and (:category is null or lower(a.category) = lower(:category))
              and (:languageCode is null or lower(a.languageCode) = lower(:languageCode))
              and (:q is null or lower(a.title) like lower(concat('%', :q, '%'))
                   or lower(a.canonicalQuestion) like lower(concat('%', :q, '%'))
                   or lower(a.answer) like lower(concat('%', :q, '%')))
            """)
    Page<HospitalInformationArticle> searchPublic(@Param("hospitalId") UUID hospitalId,
            @Param("status") HospitalInformationStatus status, @Param("now") Instant now,
            @Param("q") String q, @Param("category") String category,
            @Param("languageCode") String languageCode, Pageable pageable);

    @Query("""
            select a from HospitalInformationArticle a
            where a.articleId = :articleId and a.hospitalInformation.hospitalInformationId = :hospitalId
              and a.hospitalInformation.status = :status and a.hospitalInformation.publicVisible = true
              and (a.hospitalInformation.effectiveFrom is null or a.hospitalInformation.effectiveFrom <= :now)
              and (a.hospitalInformation.effectiveUntil is null or a.hospitalInformation.effectiveUntil > :now)
              and a.status = :status and a.publicVisible = true
              and (a.effectiveFrom is null or a.effectiveFrom <= :now)
              and (a.effectiveUntil is null or a.effectiveUntil > :now)
            """)
    java.util.Optional<HospitalInformationArticle> findPublicById(@Param("hospitalId") UUID hospitalId,
            @Param("articleId") UUID articleId, @Param("status") HospitalInformationStatus status,
            @Param("now") Instant now);
}
