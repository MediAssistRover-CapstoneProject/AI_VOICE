package com.mediassistrover.backend.controller.emergency;
import com.mediassistrover.backend.dto.common.PageResponse;
import com.mediassistrover.backend.dto.emergency.request.EmergencyRequests.Resolution;
import com.mediassistrover.backend.dto.emergency.response.EmergencyResponses.IncidentResponse;
import com.mediassistrover.backend.enums.EmergencyIncidentStatus;
import com.mediassistrover.backend.enums.EmergencyIncidentSeverity;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.EmergencyService;
import com.mediassistrover.backend.util.RequestMetadataFactory;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.UUID;
@RestController @RequestMapping("/api/v1/emergency-incidents") @RequiredArgsConstructor @Validated
@PreAuthorize("hasAnyRole('ADMIN','STAFF')") @Tag(name="Emergency Incidents")
public class EmergencyController {
    private final EmergencyService service;
    @GetMapping public PageResponse<IncidentResponse> list(@RequestParam(required=false)EmergencyIncidentStatus status,
            @RequestParam(required=false)EmergencyIncidentSeverity severity,@RequestParam(required=false)String type,
            @RequestParam(required=false)UUID mapId,@RequestParam(required=false)UUID roomId,
            @RequestParam(required=false)Instant from,@RequestParam(required=false)Instant to,
            @RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="20")@Min(1)@Max(100)int size,
            @AuthenticationPrincipal AccountPrincipal actor){return PageResponse.from(service.list(status,severity,type,mapId,roomId,from,to,actor,PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"occurredAt"))));}
    @GetMapping("/{id}") public IncidentResponse get(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal actor){return service.get(id,actor);}
    @PostMapping("/{id}/acknowledge") public IncidentResponse acknowledge(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal actor,HttpServletRequest request){return service.acknowledge(id,actor,RequestMetadataFactory.from(request));}
    @PostMapping("/{id}/start") public IncidentResponse start(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal actor,HttpServletRequest request){return service.start(id,actor,RequestMetadataFactory.from(request));}
    @PostMapping("/{id}/resolve") public IncidentResponse resolve(@PathVariable UUID id,@Valid @RequestBody Resolution body,@AuthenticationPrincipal AccountPrincipal actor,HttpServletRequest request){return service.resolve(id,body,actor,RequestMetadataFactory.from(request));}
    @PostMapping("/{id}/close") public IncidentResponse close(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal actor,HttpServletRequest request){return service.close(id,actor,RequestMetadataFactory.from(request));}
    @PostMapping("/{id}/false-alarm") public IncidentResponse falseAlarm(@PathVariable UUID id,@Valid @RequestBody Resolution body,@AuthenticationPrincipal AccountPrincipal actor,HttpServletRequest request){return service.falseAlarm(id,body,actor,RequestMetadataFactory.from(request));}
}
