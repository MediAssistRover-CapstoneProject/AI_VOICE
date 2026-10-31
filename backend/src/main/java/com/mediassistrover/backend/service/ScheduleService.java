package com.mediassistrover.backend.service;
import com.mediassistrover.backend.dto.schedule.request.ScheduleRequests.ScheduleCreateRequest;
import com.mediassistrover.backend.dto.schedule.response.ScheduleResponse;
import com.mediassistrover.backend.enums.ScheduleStatus;
import com.mediassistrover.backend.security.AccountPrincipal;
import org.springframework.data.domain.*;
import java.time.Instant;
import java.util.UUID;
public interface ScheduleService {
    ScheduleResponse create(ScheduleCreateRequest request,AccountPrincipal actor,RequestMetadata metadata);
    ScheduleResponse get(UUID id,AccountPrincipal actor);
    Page<ScheduleResponse> list(ScheduleStatus status,AccountPrincipal actor,Pageable pageable);
    ScheduleResponse activate(UUID id,AccountPrincipal actor,RequestMetadata metadata);
    ScheduleResponse pause(UUID id,AccountPrincipal actor,RequestMetadata metadata);
    ScheduleResponse retire(UUID id,AccountPrincipal actor,RequestMetadata metadata);
    int runDue(Instant now,int batchSize);
}
