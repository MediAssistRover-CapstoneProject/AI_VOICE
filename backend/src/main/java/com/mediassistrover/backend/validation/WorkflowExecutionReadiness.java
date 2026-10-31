package com.mediassistrover.backend.validation;

import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.StepRepository;
import com.mediassistrover.backend.exception.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Publish and robot allocation gates for the integrated, versioned runtime. */
@Component @RequiredArgsConstructor
public class WorkflowExecutionReadiness {
    private final StepRepository steps;
    private final com.mediassistrover.backend.mqtt.MqttStepDispatchService mqttDispatch;
    private final com.mediassistrover.backend.mqtt.MqttProperties mqttProperties;
    private final com.mediassistrover.backend.mapping.MappingProperties mappingProperties;
    private final StepExecutionPolicy executionPolicy;
    public boolean acceptsRobot(Workflow workflow, Robot robot) {
        if (workflow.getSchemaVersion()==2) return !mqttProperties.manages(robot.getRobotId());
        boolean pi=steps.findByWorkflowWorkflowIdOrderBySequenceNoAsc(workflow.getWorkflowId()).stream()
                .filter(Step::isEnabled).anyMatch(step -> switch (step.getStepType()) {
                    case MOVE, CONTROL_HARDWARE, EXECUTE_SENSING -> true;
                    case INTERACT -> "SPEAK".equals(step.getParamsJson().path("action").asText()) || step.getInputMappingJson().has("action");
                    case WAIT -> !"EVENT".equals(step.getParamsJson().path("wait_type").asText()) || step.getInputMappingJson().has("wait_type");
                    default -> false;
                });
        return !pi || mqttProperties.manages(robot.getRobotId());
    }
    public void requireReady(Workflow workflow) {
        StepContractVersions.requireWorkflow(workflow.getSchemaVersion(),workflow.getGraphVersion());
        if (workflow.getSchemaVersion()==2) return;
        for (Step step: steps.findByWorkflowWorkflowIdOrderBySequenceNoAsc(workflow.getWorkflowId())) {
            if (!step.isEnabled()) continue;
            var params=step.getParamsJson(); var mapping=step.getInputMappingJson();
            if (executionPolicy.requiresMqtt(step)) {
                mqttDispatch.requireActionSupported(step);
                continue;
            }
            switch (step.getStepType()) {
                case NOTIFY -> { } // Alert persistence and recipient ACK are integrated; explicit WAIT/EVENT waits for ACK.
                case WAIT -> { if (!"EVENT".equals(params.path("wait_type").asText()) || mapping.has("wait_type")) mqttDispatch.requireActionSupported(step); }
                case INTERACT -> { if (mapping.has("action")) blocked(step,"fixed INTERACT action"); if ("SPEAK".equals(params.path("action").asText())) mqttDispatch.requireActionSupported(step); }
                case DATA_SERVICE -> { if ("COMMIT_MAP_ARTIFACT".equals(params.path("service_key").asText()) && !mappingProperties.isEnabled() || mapping.has("service_key")) blocked(step,"artifact integration / dynamic service contract"); }
                default -> { }
            }
        }
    }
    private void blocked(Step step,String dependency) {
        throw new BusinessRuleException("Step "+step.getStepCode()+" awaits "+dependency+"; keep workflow DRAFT until integration is ready");
    }
}
