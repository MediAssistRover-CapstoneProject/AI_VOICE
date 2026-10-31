package com.mediassistrover.backend.controller.staff;

import com.mediassistrover.backend.config.OpenApiConfig;
import com.mediassistrover.backend.dto.staff.response.StaffMeResponse;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.StaffMeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/staff")
@RequiredArgsConstructor
@PreAuthorize("hasRole('STAFF')")
@Tag(name = "Staff Profile", description = "Thông tin của Staff đang đăng nhập")
@SecurityRequirement(name = OpenApiConfig.USER_BEARER_SCHEME)
public class StaffMeController {
    private final StaffMeService service;

    @GetMapping("/me")
    @Operation(summary = "Lấy Account, StaffProfile và ca trực đang hiệu lực của Staff hiện tại")
    public StaffMeResponse me(@AuthenticationPrincipal AccountPrincipal principal) {
        return service.get(principal);
    }
}
