package com.mediassistrover.backend.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.dto.facility.request.FacilityRequests.HospitalInformationArticleRequest;
import com.mediassistrover.backend.dto.facility.response.FacilityResponses.HospitalInformationArticleResponse;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.HospitalInformationStatus;
import com.mediassistrover.backend.exception.*;
import com.mediassistrover.backend.mapper.FacilityMapper;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HospitalInformationArticleServiceImpl implements HospitalInformationArticleService {
    private final HospitalInformationArticleRepository articleRepository;
    private final HospitalInformationRepository hospitalRepository;
    private final AccountRepository accountRepository;
    private final FacilityMapper mapper;
    private final AuditService auditService;

    @Override @Transactional
    public HospitalInformationArticleResponse create(UUID hospitalId, HospitalInformationArticleRequest request,
                                                     UUID actorId, RequestMetadata metadata) {
        validate(request);
        if (articleRepository.existsByHospitalInformationHospitalInformationIdAndArticleCodeIgnoreCaseAndLanguageCodeIgnoreCase(
                hospitalId, request.articleCode(), request.languageCode())) {
            throw new ConflictException("Article code and language already exist in this hospital");
        }
        Account actor = account(actorId);
        HospitalInformationArticle article = mapper.toEntity(request);
        article.setHospitalInformation(hospital(hospitalId));
        article.setUpdatedBy(actor);
        article = articleRepository.save(article);
        auditService.record(actor, "HOSPITAL_ARTICLE_CREATED", "HospitalInformationArticle",
                article.getArticleId(), null, Map.of("hospitalInformationId", hospitalId), metadata);
        return mapper.toResponse(article);
    }

    @Override @Transactional
    public HospitalInformationArticleResponse update(UUID hospitalId, UUID articleId,
                                                     HospitalInformationArticleRequest request,
                                                     UUID actorId, RequestMetadata metadata) {
        validate(request);
        HospitalInformationArticle article = article(hospitalId, articleId);
        if (article.getStatus() == HospitalInformationStatus.RETIRED) {
            throw new BusinessRuleException("Retired hospital article cannot be updated");
        }
        if ((!article.getArticleCode().equalsIgnoreCase(request.articleCode())
                || !article.getLanguageCode().equalsIgnoreCase(request.languageCode()))
                && articleRepository.existsByHospitalInformationHospitalInformationIdAndArticleCodeIgnoreCaseAndLanguageCodeIgnoreCase(
                hospitalId, request.articleCode(), request.languageCode())) {
            throw new ConflictException("Article code and language already exist in this hospital");
        }
        Account actor = account(actorId);
        mapper.update(request, article);
        article.setUpdatedBy(actor);
        article.setStatus(HospitalInformationStatus.DRAFT);
        auditService.record(actor, "HOSPITAL_ARTICLE_UPDATED", "HospitalInformationArticle",
                articleId, null, Map.of("status", "DRAFT"), metadata);
        return mapper.toResponse(article);
    }

    @Override @Transactional
    public HospitalInformationArticleResponse publish(UUID hospitalId, UUID articleId,
                                                      UUID actorId, RequestMetadata metadata) {
        HospitalInformationArticle article = article(hospitalId, articleId);
        if (article.getStatus() != HospitalInformationStatus.DRAFT) {
            throw new BusinessRuleException("Only DRAFT hospital article can be published");
        }
        Account actor = account(actorId);
        article.setStatus(HospitalInformationStatus.PUBLISHED);
        article.setUpdatedBy(actor);
        auditService.record(actor, "HOSPITAL_ARTICLE_PUBLISHED", "HospitalInformationArticle",
                articleId, null, Map.of("publicVisible", article.isPublicVisible()), metadata);
        return mapper.toResponse(article);
    }

    @Override @Transactional
    public HospitalInformationArticleResponse retire(UUID hospitalId, UUID articleId,
                                                     UUID actorId, RequestMetadata metadata) {
        HospitalInformationArticle article = article(hospitalId, articleId);
        Account actor = account(actorId);
        article.setStatus(HospitalInformationStatus.RETIRED);
        article.setUpdatedBy(actor);
        auditService.record(actor, "HOSPITAL_ARTICLE_RETIRED", "HospitalInformationArticle",
                articleId, null, Map.of(), metadata);
        return mapper.toResponse(article);
    }

    @Override public HospitalInformationArticleResponse get(UUID hospitalId, UUID articleId) {
        return mapper.toResponse(article(hospitalId, articleId));
    }

    @Override public Page<HospitalInformationArticleResponse> list(UUID hospitalId,
                                                                  HospitalInformationStatus status,
                                                                  Pageable pageable) {
        hospital(hospitalId);
        return articleRepository.search(hospitalId, status, pageable).map(mapper::toResponse);
    }

    private void validate(HospitalInformationArticleRequest request) {
        if (request.effectiveFrom() != null && request.effectiveUntil() != null
                && !request.effectiveUntil().isAfter(request.effectiveFrom())) {
            throw new BusinessRuleException("effectiveUntil must be after effectiveFrom");
        }
        JsonNode keywords = request.keywordsJson();
        if (!keywords.isArray() || keywords.size() > 50) {
            throw new BusinessRuleException("keywordsJson must be an array with at most 50 values");
        }
        keywords.forEach(value -> {
            if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 100) {
                throw new BusinessRuleException("Each article keyword must be non-blank text up to 100 characters");
            }
        });
    }

    private HospitalInformationArticle article(UUID hospitalId, UUID articleId) {
        HospitalInformationArticle article = articleRepository.findById(articleId)
                .orElseThrow(() -> new ResourceNotFoundException("HospitalInformationArticle not found: " + articleId));
        if (!article.getHospitalInformation().getHospitalInformationId().equals(hospitalId)) {
            throw new ResourceNotFoundException("HospitalInformationArticle not found in hospital: " + articleId);
        }
        return article;
    }
    private HospitalInformation hospital(UUID id) { return hospitalRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("HospitalInformation not found: " + id)); }
    private Account account(UUID id) { return accountRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + id)); }
}
