package com.mediassistrover.backend.controller.shift;

import com.mediassistrover.backend.dto.common.PageResponse;
import com.mediassistrover.backend.dto.shift.response.ShiftResponses.ShiftResponse;
import com.mediassistrover.backend.enums.ShiftStatus;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.ShiftService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/staff/shifts")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasRole('STAFF')")
@Tag(name = "Staff Shifts", description = "Read-only access to the authenticated Staff member's assignments")
public class StaffShiftController {
    private final ShiftService service;

    @GetMapping
    public PageResponse<ShiftResponse> listOwn(@AuthenticationPrincipal AccountPrincipal actor,
                                               @RequestParam(required = false) ShiftStatus status,
                                               @RequestParam(defaultValue = "0") @Min(0) int page,
                                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.from(service.listOwn(actor.accountId(), status,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startsAt"))));
    }

    @GetMapping("/{shiftId}")
    public ShiftResponse getOwn(@PathVariable UUID shiftId, @AuthenticationPrincipal AccountPrincipal actor) {
        return service.getOwn(shiftId, actor.accountId());
    }
}
