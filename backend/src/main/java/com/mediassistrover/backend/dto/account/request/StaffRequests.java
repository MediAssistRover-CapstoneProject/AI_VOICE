package com.mediassistrover.backend.dto.account.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

public final class StaffRequests {
    private StaffRequests() {}
    @Schema(name="StaffCreateRequest") public record Create(
            @NotBlank @Size(min=4,max=100) @Pattern(regexp="^[a-zA-Z0-9._-]+$") String username,
            @NotBlank @Size(min=8,max=72) String temporaryPassword,
            @NotBlank @Size(max=160) String fullName,
            @NotBlank @Email @Size(max=254) String email,
            @Size(max=32) @Pattern(regexp="^\\+?[0-9][0-9 .-]{7,30}$") String phone,
            @NotBlank @Size(max=64) String staffCode,
            @Size(max=64) String departmentCode,
            @Size(max=100) String jobTitle,
            @Size(max=20) String contactExtension) {}
    @Schema(name="StaffUpdateRequest") public record Update(
            @NotBlank @Size(max=160) String fullName,
            @NotBlank @Email @Size(max=254) String email,
            @Size(max=32) @Pattern(regexp="^\\+?[0-9][0-9 .-]{7,30}$") String phone,
            @NotBlank @Size(max=64) String staffCode,
            @Size(max=64) String departmentCode,
            @Size(max=100) String jobTitle,
            @Size(max=20) String contactExtension) {}
}
