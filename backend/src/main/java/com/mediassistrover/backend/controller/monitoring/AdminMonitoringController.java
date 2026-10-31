package com.mediassistrover.backend.controller.monitoring;
import com.mediassistrover.backend.dto.common.PageResponse;
import com.mediassistrover.backend.dto.monitoring.request.MonitoringRequests.HeatmapCreate;
import com.mediassistrover.backend.dto.monitoring.response.MonitoringResponses.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.MonitoringService;
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
import java.time.Instant;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/admin/monitoring") @RequiredArgsConstructor @Validated
@PreAuthorize("hasRole('ADMIN')") @Tag(name="Admin Monitoring History")
public class AdminMonitoringController {
    private final MonitoringService service;
    @GetMapping("/detections") public PageResponse<DetectionResponse> detections(@RequestParam(required=false) UUID robotId,@RequestParam(required=false) DetectionEventType type,@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,@RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size){return PageResponse.from(service.listDetections(robotId,type,from,to,page(page,size,"detectedAt")));}
    @GetMapping("/detections/{id}") public DetectionResponse detection(@PathVariable UUID id){return service.getDetection(id);}
    @GetMapping("/crowd-density") public PageResponse<CrowdDensityResponse> crowd(@RequestParam(required=false) UUID zoneId,@RequestParam(required=false) CrowdDensityLevel level,@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,@RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size){return PageResponse.from(service.listCrowdDensity(zoneId,level,from,to,page(page,size,"capturedAt")));}
    @GetMapping("/crowd-density/{id}") public CrowdDensityResponse crowd(@PathVariable UUID id){return service.getCrowdDensity(id);}
    @PostMapping("/heatmaps") @ResponseStatus(HttpStatus.CREATED) @Operation(summary="Store a generated heatmap; does not run an AI algorithm")
    public HeatmapResponse createHeatmap(@Valid @RequestBody HeatmapCreate body,@AuthenticationPrincipal AccountPrincipal actor,HttpServletRequest request){return service.createHeatmap(body,actor.accountId(),RequestMetadataFactory.from(request));}
    @GetMapping("/heatmaps") public PageResponse<HeatmapResponse> heatmaps(@RequestParam(required=false) UUID mapId,@RequestParam(required=false) String type,@RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size){return PageResponse.from(service.listHeatmaps(mapId,type,page(page,size,"generatedAt")));}
    @GetMapping("/heatmaps/{id}") public HeatmapResponse heatmap(@PathVariable UUID id){return service.getHeatmap(id);}
    private Pageable page(int page,int size,String property){return PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,property));}
}
