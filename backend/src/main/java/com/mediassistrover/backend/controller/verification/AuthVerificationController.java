package com.mediassistrover.backend.controller.verification;
import com.mediassistrover.backend.dto.verification.request.AuthVerificationRequests.Create;
import com.mediassistrover.backend.dto.verification.response.AuthVerificationResponse;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.AuthVerificationService;
import com.mediassistrover.backend.util.RequestMetadataFactory;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import com.mediassistrover.backend.dto.verification.request.QrVerificationRequests.*;
import com.mediassistrover.backend.dto.verification.response.QrChallengeResponse;
@RestController @RequestMapping("/api/v1") @RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','STAFF')") @Tag(name="Delivery Authentication")
public class AuthVerificationController {
    private final AuthVerificationService service; private final com.mediassistrover.backend.service.QrVerificationService qrService;
    @io.swagger.v3.oas.annotations.Operation(summary="Issue a Staff confirmation grant", description="ADMIN or STAFF owning/assigned to the RUNNING Job. Purpose must match the AUTHENTICATE Step. Only STAFF_CONFIRMATION is supported here; QR_CODE requires a separate proof verifier. Same actor retry returns the existing unexpired grant without extending its TTL. Scope violations return 403.")
    @PostMapping("/jobs/{jobId}/auth-verifications") @ResponseStatus(HttpStatus.CREATED) public AuthVerificationResponse create(@PathVariable UUID jobId,@Valid @RequestBody Create body,@AuthenticationPrincipal AccountPrincipal actor,HttpServletRequest request){return service.create(jobId,body,actor,RequestMetadataFactory.from(request));}
    @GetMapping("/auth-verifications/{id}") public AuthVerificationResponse get(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal actor){return service.get(id,actor);}
    @PostMapping("/jobs/{jobId}/qr-challenges") @ResponseStatus(HttpStatus.CREATED) public QrChallengeResponse issueQr(@PathVariable UUID jobId,@Valid @RequestBody Issue body,@AuthenticationPrincipal AccountPrincipal actor,HttpServletRequest request){return qrService.issue(jobId,body,actor,RequestMetadataFactory.from(request));}
    @PostMapping("/jobs/{jobId}/qr-verifications") public AuthVerificationResponse verifyQr(@PathVariable UUID jobId,@Valid @RequestBody Verify body,@AuthenticationPrincipal AccountPrincipal actor,HttpServletRequest request){return qrService.verify(jobId,body.proof(),actor,RequestMetadataFactory.from(request));}
}
