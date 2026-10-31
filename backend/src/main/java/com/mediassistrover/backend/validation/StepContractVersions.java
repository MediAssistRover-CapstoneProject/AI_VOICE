package com.mediassistrover.backend.validation;

import com.mediassistrover.backend.exception.BusinessRuleException;

/** Version identities. WorkflowExecutionReadiness gates the integrated schema 3 subset. */
public final class StepContractVersions {
    public static final String PROTOCOL = "1.0";
    public static final String MQTT_CONTRACT = "MAR-STEP-2";
    public static final int STEP_SCHEMA = 2;
    public static final int PLANNED_STEP_SCHEMA = 3;
    public static final int GRAPH = 3;

    private StepContractVersions() {}

    public static void requireWorkflow(int schemaVersion, int graphVersion) {
        if (schemaVersion != STEP_SCHEMA && schemaVersion != PLANNED_STEP_SCHEMA)
            throw new BusinessRuleException("Unsupported Step contract schemaVersion: " + schemaVersion + "; supported: 2, 3");
        if (graphVersion != GRAPH)
            throw new BusinessRuleException("Unsupported Workflow graphVersion: " + graphVersion + "; supported: " + GRAPH);
    }

    public static void requireJob(int schemaVersion, int graphVersion) {
        requireWorkflow(schemaVersion, graphVersion);
    }

    /** Conservative guard for legacy callers; schema 3 needs per-workflow readiness validation. */
    public static void requireExecutableWorkflow(int schemaVersion) {
        if (schemaVersion != STEP_SCHEMA)
            throw new BusinessRuleException("Workflow schema 3 awaits MQTT orchestrator and versioned Server handlers; keep it DRAFT until integration is ready");
    }
}
