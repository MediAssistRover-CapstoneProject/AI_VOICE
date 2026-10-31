package com.mediassistrover.backend.service;

import com.mediassistrover.backend.dto.setting.request.SystemSettingRequests.Create;
import com.mediassistrover.backend.dto.setting.request.SystemSettingRequests.Update;
import com.mediassistrover.backend.dto.setting.response.SystemSettingResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface SystemSettingService {
    SystemSettingResponse create(Create request, UUID actorId, RequestMetadata metadata);
    SystemSettingResponse update(UUID id, Update request, UUID actorId, RequestMetadata metadata);
    SystemSettingResponse get(UUID id);
    Page<SystemSettingResponse> list(String category, Pageable pageable);
    void delete(UUID id, UUID actorId, RequestMetadata metadata);
}
