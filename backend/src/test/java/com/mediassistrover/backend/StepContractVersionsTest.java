package com.mediassistrover.backend;

import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.validation.StepContractVersions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepContractVersionsTest {
    @Test
    void legacyJobsAndWorkflowsRemainSupported() {
        assertThatCode(() -> StepContractVersions.requireWorkflow(2, 3)).doesNotThrowAnyException();
        assertThatCode(() -> StepContractVersions.requireJob(2, 3)).doesNotThrowAnyException();
    }

    @Test
    void newSchemaCanBeValidatedButCannotExecuteBeforeMqttIntegration() {
        assertThat(StepContractVersions.PLANNED_STEP_SCHEMA).isEqualTo(3);
        assertThatCode(() -> StepContractVersions.requireWorkflow(3, 3)).doesNotThrowAnyException();
        assertThatCode(() -> StepContractVersions.requireJob(3, 3)).doesNotThrowAnyException();
        assertThatThrownBy(() -> StepContractVersions.requireExecutableWorkflow(3))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("MQTT orchestrator");
        assertThatCode(() -> StepContractVersions.requireExecutableWorkflow(2)).doesNotThrowAnyException();
    }

    @Test
    void graphAndTransportVersionsAreIndependentOfThePlannedStepSchema() {
        assertThat(StepContractVersions.PROTOCOL).isEqualTo("1.0");
        assertThat(StepContractVersions.MQTT_CONTRACT).isEqualTo("MAR-STEP-2");
        assertThat(StepContractVersions.GRAPH).isEqualTo(3);
        assertThatThrownBy(() -> StepContractVersions.requireJob(2, 2))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("graphVersion: 2");
    }
}
