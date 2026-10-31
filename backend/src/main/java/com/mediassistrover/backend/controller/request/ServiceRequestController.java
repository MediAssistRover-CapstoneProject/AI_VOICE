package com.mediassistrover.backend.controller.request;
import com.mediassistrover.backend.dto.common.PageResponse;
import com.mediassistrover.backend.dto.request.request.ServiceRequestRequests.*;
import com.mediassistrover.backend.dto.request.response.ServiceRequestResponses.*;
import com.mediassistrover.backend.enums.ServiceRequestStatus;
import com.mediassistrover.backend.enums.ServiceRequestType;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.ServiceRequestService;
import com.mediassistrover.backend.util.RequestMetadataFactory;
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

@RestController @RequestMapping("/api/v1") @RequiredArgsConstructor @Validated
@PreAuthorize("hasAnyRole('ADMIN','STAFF')") @Tag(name="Service Requests")
public class ServiceRequestController {
    private final ServiceRequestService service;
    @PostMapping("/information-requests") @ResponseStatus(HttpStatus.CREATED) public ServiceRequestResponse information(@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody InformationCreateRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.createInformation(b,key,a,RequestMetadataFactory.from(r));}
    @PostMapping("/navigation-requests") @ResponseStatus(HttpStatus.CREATED) public NavigationResponse navigation(@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody NavigationCreateRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.createNavigation(b,key,a,RequestMetadataFactory.from(r));}
    @PostMapping("/escort-requests") @ResponseStatus(HttpStatus.CREATED) public EscortResponse escort(@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody EscortCreateRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.createEscort(b,key,a,RequestMetadataFactory.from(r));}
    @PostMapping("/operational-requests") @ResponseStatus(HttpStatus.CREATED) public ServiceRequestResponse operational(@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody OperationalCreateRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.createOperational(b,key,a,RequestMetadataFactory.from(r));}
    @GetMapping("/service-requests") public PageResponse<ServiceRequestResponse> list(@RequestParam(required=false)ServiceRequestStatus status,@RequestParam(required=false)ServiceRequestType requestType,@RequestParam(required=false)UUID robotId,@RequestParam(required=false)Instant from,@RequestParam(required=false)Instant to,@RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="20")@Min(1)@Max(100)int size,@AuthenticationPrincipal AccountPrincipal a){return PageResponse.from(service.list(status,requestType,robotId,from,to,a,PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"requestedAt"))));}
    @GetMapping("/service-requests/{id}") public ServiceRequestResponse get(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal a){return service.get(id,a);}
    @PostMapping("/service-requests/{id}/submit") public ServiceRequestResponse submit(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.submit(id,a,RequestMetadataFactory.from(r));}
    @PostMapping("/service-requests/{id}/cancel") public ServiceRequestResponse cancel(@PathVariable UUID id,@Valid @RequestBody CancelRequest b,@AuthenticationPrincipal AccountPrincipal a,HttpServletRequest r){return service.cancel(id,b.reason(),a,RequestMetadataFactory.from(r));}
}
