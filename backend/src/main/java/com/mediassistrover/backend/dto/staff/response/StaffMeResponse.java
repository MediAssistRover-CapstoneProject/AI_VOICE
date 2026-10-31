package com.mediassistrover.backend.dto.staff.response;

import com.mediassistrover.backend.dto.account.response.AccountResponse;
import com.mediassistrover.backend.dto.shift.response.ShiftResponses.ShiftResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StaffMeResponse(
        AccountResponse account,
        StaffProfileSummary staffProfile,
        List<ShiftResponse> activeShifts
) {
    public record StaffProfileSummary(
            UUID staffProfileId,
            String staffCode,
            String departmentCode,
            String jobTitle,
            String contactExtension,
            boolean active,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
