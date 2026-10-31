package com.mediassistrover.backend.dto.schedule.response;
import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.*;
import java.time.Instant;
import java.util.UUID;
public record ScheduleResponse(UUID scheduleId,UUID workflowId,UUID robotId,UUID createdByAccountId,String scheduleCode,
    String scheduleName,ScheduleType scheduleType,String timezone,Instant startsAt,Instant endsAt,Integer intervalSec,
    String cronExpression,JsonNode inputTemplateJson,int priority,MisfirePolicy misfirePolicy,ScheduleStatus status,
    Instant nextRunAt,Instant lastRunAt,long rowVersion,Instant createdAt,Instant updatedAt) {}
