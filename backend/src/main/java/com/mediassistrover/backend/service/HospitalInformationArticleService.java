package com.mediassistrover.backend.service;

import com.mediassistrover.backend.dto.facility.request.FacilityRequests.HospitalInformationArticleRequest;
import com.mediassistrover.backend.dto.facility.response.FacilityResponses.HospitalInformationArticleResponse;
import com.mediassistrover.backend.enums.HospitalInformationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface HospitalInformationArticleService {
    HospitalInformationArticleResponse create(UUID hospitalId, HospitalInformationArticleRequest request,
                                              UUID actorId, RequestMetadata metadata);
    HospitalInformationArticleResponse update(UUID hospitalId, UUID articleId,
                                              HospitalInformationArticleRequest request,
                                              UUID actorId, RequestMetadata metadata);
    HospitalInformationArticleResponse publish(UUID hospitalId, UUID articleId,
                                               UUID actorId, RequestMetadata metadata);
    HospitalInformationArticleResponse retire(UUID hospitalId, UUID articleId,
                                              UUID actorId, RequestMetadata metadata);
    HospitalInformationArticleResponse get(UUID hospitalId, UUID articleId);
    Page<HospitalInformationArticleResponse> list(UUID hospitalId, HospitalInformationStatus status,
                                                  Pageable pageable);
}
