package com.mediassistrover.backend.dto.verification.request;
import com.mediassistrover.backend.enums.AuthVerificationPurpose;
import jakarta.validation.constraints.*;
import java.util.UUID;
public final class QrVerificationRequests {
    private QrVerificationRequests() { }
    public record Issue(@NotNull UUID authJobStepId,@NotNull AuthVerificationPurpose purpose,@Min(30) @Max(300) int validForSeconds) { }
    public record Verify(@NotBlank @Size(max=256) String proof) { }
}
