package com.mediassistrover.backend.controller.account;
import com.mediassistrover.backend.dto.account.request.StaffRequests;
import com.mediassistrover.backend.dto.account.response.StaffResponse;
import com.mediassistrover.backend.dto.common.PageResponse;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.StaffService;
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

@RestController @RequestMapping("/api/v1/admin/staff") @RequiredArgsConstructor @Validated
@PreAuthorize("hasRole('ADMIN')") @Tag(name="Admin Staff", description="Admin provisions and deactivates Staff accounts")
public class AdminStaffController {
    private final StaffService service;
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public StaffResponse create(@Valid @RequestBody StaffRequests.Create body,@AuthenticationPrincipal AccountPrincipal actor,HttpServletRequest request){return service.create(body,actor.accountId(),RequestMetadataFactory.from(request));}
    @GetMapping public PageResponse<StaffResponse> list(@RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size){return PageResponse.from(service.list(PageRequest.of(page,size,Sort.by("staffCode"))));}
    @GetMapping("/{id}") public StaffResponse get(@PathVariable UUID id){return service.get(id);}
    @PutMapping("/{id}") public StaffResponse update(@PathVariable UUID id,@Valid @RequestBody StaffRequests.Update body,@AuthenticationPrincipal AccountPrincipal actor,HttpServletRequest request){return service.update(id,body,actor.accountId(),RequestMetadataFactory.from(request));}
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal actor,HttpServletRequest request){service.deactivate(id,actor.accountId(),RequestMetadataFactory.from(request));}
}
