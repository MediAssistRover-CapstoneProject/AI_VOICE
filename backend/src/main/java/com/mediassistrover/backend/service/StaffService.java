package com.mediassistrover.backend.service;
import com.mediassistrover.backend.dto.account.request.StaffRequests;
import com.mediassistrover.backend.dto.account.response.StaffResponse;
import org.springframework.data.domain.*;
import java.util.UUID;
public interface StaffService {
    StaffResponse create(StaffRequests.Create request, UUID actorId, RequestMetadata metadata);
    StaffResponse update(UUID id, StaffRequests.Update request, UUID actorId, RequestMetadata metadata);
    StaffResponse get(UUID id);
    Page<StaffResponse> list(Pageable pageable);
    void deactivate(UUID id, UUID actorId, RequestMetadata metadata);
}
