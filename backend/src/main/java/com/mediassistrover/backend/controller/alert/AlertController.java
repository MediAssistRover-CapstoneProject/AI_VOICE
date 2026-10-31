package com.mediassistrover.backend.controller.alert;
import com.mediassistrover.backend.dto.alert.response.AlertResponses.AlertResponse;
import com.mediassistrover.backend.dto.common.PageResponse;
import com.mediassistrover.backend.enums.AlertStatus;
import com.mediassistrover.backend.enums.AlertSeverity;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.AlertService;
import com.mediassistrover.backend.util.RequestMetadataFactory;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.UUID;
@RestController @RequestMapping("/api/v1/alerts") @RequiredArgsConstructor @Validated
@PreAuthorize("hasAnyRole('ADMIN','STAFF')") @Tag(name="Alerts")
public class AlertController {
    private final AlertService service;
    @GetMapping public PageResponse<AlertResponse> list(@RequestParam(required=false)AlertStatus status,
            @RequestParam(required=false)AlertSeverity severity,@RequestParam(required=false)String type,
            @RequestParam(required=false)Instant from,@RequestParam(required=false)Instant to,
            @RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="20")@Min(1)@Max(100)int size,
            @AuthenticationPrincipal AccountPrincipal actor){return PageResponse.from(service.list(status,severity,type,from,to,actor,PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"createdAt"))));}
    @GetMapping("/{id}") public AlertResponse get(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal actor){return service.get(id,actor);}
    @PostMapping("/{id}/acknowledge") public AlertResponse acknowledge(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal actor,HttpServletRequest request){return service.acknowledge(id,actor,RequestMetadataFactory.from(request));}
    @PostMapping("/{id}/resolve") public AlertResponse resolve(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal actor,HttpServletRequest request){return service.resolve(id,actor,RequestMetadataFactory.from(request));}
    @PostMapping("/{id}/close") public AlertResponse close(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal actor,HttpServletRequest request){return service.close(id,actor,RequestMetadataFactory.from(request));}
}
