package com.mediassistrover.backend.dto.schedule.request;
import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.*;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;
public final class ScheduleRequests {
    private ScheduleRequests() {}
    public record ScheduleCreateRequest(@NotNull UUID workflowId,UUID robotId,@NotBlank @Size(max=64) String scheduleCode,
        @NotBlank @Size(max=160) String scheduleName,@NotNull ScheduleType scheduleType,@NotBlank @Size(max=64) String timezone,
        @NotNull Instant startsAt,Instant endsAt,@Min(1) Integer intervalSec,@Size(max=120) String cronExpression,
        @NotNull JsonNode inputTemplateJson,@Min(0) @Max(100) int priority,@NotNull MisfirePolicy misfirePolicy) {}
}
