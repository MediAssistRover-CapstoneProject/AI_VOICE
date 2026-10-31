package com.mediassistrover.backend.controller.robot;

import com.mediassistrover.backend.dto.common.PageResponse;
import com.mediassistrover.backend.dto.robot.request.RobotRequests.*;
import com.mediassistrover.backend.dto.robot.response.RobotResponses.*;
import com.mediassistrover.backend.enums.RobotStatus;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.RobotService;
import com.mediassistrover.backend.util.RequestMetadataFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.time.Instant;

@RestController @RequestMapping("/api/v1/robots") @RequiredArgsConstructor @Validated
@PreAuthorize("hasAnyRole('ADMIN','STAFF')")
@Tag(name="Robots",description="ADMIN quản lý; ADMIN/STAFF theo dõi")
public class RobotController {
    private final RobotService service;
    private final com.mediassistrover.backend.service.RobotRealtimeSnapshotService realtime;
    private final com.mediassistrover.backend.service.RobotRealtimeEventStream realtimeEvents;
    @GetMapping public PageResponse<RobotResponse> list(@RequestParam(required=false)RobotStatus status,@RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="20")@Min(1)@Max(100)int size){return PageResponse.from(service.list(status,PageRequest.of(page,size,Sort.by("robotCode"))));}
    @GetMapping("/{id}") public RobotResponse get(@PathVariable UUID id){return service.get(id);}
    @GetMapping("/{id}/snapshot") public com.mediassistrover.backend.service.RobotRealtimeSnapshotService.Snapshot snapshot(@PathVariable UUID id){return realtime.snapshot(id);}
    @GetMapping(value="/{id}/stream",produces=org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE) public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal actor){return realtimeEvents.subscribe(id,actor);}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('ADMIN')") public RobotResponse create(@Valid @RequestBody RobotCreateRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.create(b,a.accountId(),RequestMetadataFactory.from(r));}
    @PutMapping("/{id}") @PreAuthorize("hasRole('ADMIN')") public RobotResponse update(@PathVariable UUID id,@Valid @RequestBody RobotUpdateRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.update(id,b,a.accountId(),RequestMetadataFactory.from(r));}
    @PostMapping("/{id}/provision-secret") @PreAuthorize("hasRole('ADMIN')") @Operation(summary="Cấp device secret một lần",description="Secret chỉ xuất hiện trong response này và được lưu dạng BCrypt hash") public DeviceSecretResponse provision(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.provisionSecret(id,a.accountId(),RequestMetadataFactory.from(r));}
    @PostMapping("/{id}/rotate-secret") @PreAuthorize("hasRole('ADMIN')") public DeviceSecretResponse rotate(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.rotateSecret(id,a.accountId(),RequestMetadataFactory.from(r));}
    @PostMapping("/{id}/maintenance") @PreAuthorize("hasRole('ADMIN')") public RobotResponse maintenance(@PathVariable UUID id,@Valid @RequestBody RobotActionRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.maintenance(id,b.reason(),a.accountId(),RequestMetadataFactory.from(r));}
    @PostMapping("/{id}/reconcile") public RobotResponse reconcile(@PathVariable UUID id,@Valid @RequestBody RobotActionRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.reconcile(id,b.reason(),a,RequestMetadataFactory.from(r));}
    @GetMapping("/{id}/status-history") public PageResponse<RobotStatusHistoryResponse> statusHistory(@PathVariable UUID id,@RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="20")@Min(1)@Max(100)int size){return PageResponse.from(service.statusHistory(id,PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"recordedAt"))));}
    @GetMapping("/{id}/location-history") public PageResponse<RobotLocationResponse> locationHistory(@PathVariable UUID id,@RequestParam(required=false)Instant from,@RequestParam(required=false)Instant to,@RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="20")@Min(1)@Max(100)int size){return PageResponse.from(service.locationHistory(id,from,to,PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"capturedAt"))));}
}
