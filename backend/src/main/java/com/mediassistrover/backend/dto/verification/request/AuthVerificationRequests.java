package com.mediassistrover.backend.dto.verification.request;
import com.mediassistrover.backend.enums.*;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.util.UUID;
public final class AuthVerificationRequests {
    private AuthVerificationRequests() { }
    @Schema(name="AuthVerificationCreateRequest") public record Create(@NotNull UUID authJobStepId,@NotNull AuthVerificationMethod method,
                         @NotNull AuthVerificationPurpose purpose,@Min(30) @Max(900) int validForSeconds) { }
}
