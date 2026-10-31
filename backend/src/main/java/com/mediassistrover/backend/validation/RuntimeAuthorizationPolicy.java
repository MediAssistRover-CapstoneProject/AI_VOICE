package com.mediassistrover.backend.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.security.AccountPrincipal;
import org.springframework.security.access.AccessDeniedException;
import java.util.Set;

/** Pure authorization rules shared by runtime entry points; never trusts callback output as proof. */
public final class RuntimeAuthorizationPolicy {
    private RuntimeAuthorizationPolicy() {}

    public static void requireJobScope(Job job, AccountPrincipal actor) {
        if (actor.role() == Role.ADMIN) return;
        boolean assigned = job.getAssignedStaffAccount() != null
                && job.getAssignedStaffAccount().getAccountId().equals(actor.accountId());
        boolean owner = job.getServiceRequest() != null && job.getServiceRequest().getRequestedByAccount() != null
                && job.getServiceRequest().getRequestedByAccount().getAccountId().equals(actor.accountId());
        if (actor.role() != Role.STAFF || (!assigned && !owner))
            throw new AccessDeniedException("Job is outside your scope");
    }

    public static void requireRunning(Job job) {
        if (job.getStatus() != JobStatus.RUNNING) throw new BusinessRuleException("Job must be RUNNING");
    }

    public static void requireDeviceCallback(JobStep step) {
        StepType type = step.getStep().getStepType();
        if (type != StepType.INTERACT && type.getExecutorKind(step.getJobTask().getJob().getSchemaVersion()) != StepExecutorKind.SERVER) return;
        // INTERACT is dispatched by Server but ordinary display/audio/patient input finishes on Device.
        if (type == StepType.INTERACT) {
            JsonNode input = step.getResolvedInputsJson();
            JsonNode params = step.getStep().getParamsJson();
            String action = field(input, params, "action", "DISPLAY");
            // Resolved mappings must never downgrade the authority declared by the definition.
            if (!requiresStaff(input) && !requiresStaff(params)
                    && Set.of("DISPLAY", "SPEAK", "CAPTURE_INPUT").contains(action)) return;
        }
        throw new AccessDeniedException("Device cannot complete or fail this Server/Staff Step");
    }

    private static boolean requiresStaff(JsonNode config) {
        if (config == null) return false;
        return Set.of("STAFF", "ADMIN").contains(config.path("actor").asText())
                || Set.of("STAFF", "ADMIN").contains(config.path("required_role").asText())
                || Set.of("CONFIRM_COMPARTMENT_CLOSED", "VERIFY_COMPARTMENT_LOCKED",
                    "STAFF_REVIEW", "REMOTE_REVIEW", "CONFIRM_DISPATCH")
                    .contains(config.path("prompt_key").asText());
    }

    public static AuthVerificationPurpose purpose(JobStep step) {
        String purpose = field(step.getStep().getParamsJson(), null, "purpose", "");
        try { return AuthVerificationPurpose.valueOf(purpose); }
        catch (IllegalArgumentException ex) { throw new BusinessRuleException("AUTHENTICATE requires a configured purpose"); }
    }

    private static String field(JsonNode preferred, JsonNode fallback, String key, String defaultValue) {
        if (preferred != null && preferred.path(key).isTextual()) return preferred.path(key).asText();
        return fallback == null ? defaultValue : fallback.path(key).asText(defaultValue);
    }
}
