package com.mediassistrover.backend.mqtt;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.validation.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class MqttStepDispatchService {
    private final MqttProperties properties;
    private final com.mediassistrover.backend.validation.StepExecutionPolicy executionPolicy;
    private final MqttSessionRegistry registry;
    private final RobotMqttSessionRepository sessions;
    private final RobotCommandOutboxRepository commands;
    private final RobotRepository robots;
    private final StepInputContractValidator inputs;
    private final StepInputScopeValidator scope;
    private final MqttContractValidator contracts;
    private final MqttWire wire;
    private final Clock clock;
    private final com.mediassistrover.backend.service.OperationAuthorizationService operationAuthorization;
    private final com.mediassistrover.backend.service.NavigationProfileService navigationProfiles;
    private final com.mediassistrover.backend.mapping.MappingLifecycleService mapping;
    private final com.mediassistrover.backend.ai.AiSensingService ai;
    private final MqttRolloutService rollout;
    private final com.mediassistrover.backend.service.NavigationSessionService navigationSessions;

    public void requireActionSupported(Step step) {
        if(!executionPolicy.requiresMqtt(step))throw new BusinessRuleException("Step is backend-owned and cannot use MQTT dispatch");
        if (!properties.isEnabled() || !properties.isDispatchEnabled() || properties.getRobotIds().isEmpty()
                || !properties.getEnabledStepTypes().contains(step.getStepType().name()))
            throw new BusinessRuleException("Step "+step.getStepCode()+" awaits configured MQTT dispatch; keep workflow DRAFT");
        ObjectNode params=((ObjectNode)step.getParamsJson()).deepCopy();params.setAll((ObjectNode)step.getInputMappingJson());
        if (step.getStepType()==StepType.CONTROL_HARDWARE && !Set.of("LOCK_COMPARTMENT","LOCK").contains(params.path("action").asText())
                && !(properties.isAllowCommissioningUnlock() && Set.of("UNLOCK_COMPARTMENT","UNLOCK").contains(params.path("action").asText()) && !step.getInputMappingJson().has("action")))
            throw new BusinessRuleException("Official UNLOCK awaits Pi STAFF_VERIFIED enforcement and a confirmed HELLO/output contract; keep workflow DRAFT");
        if (step.getStepType()==StepType.EXECUTE_SENSING && "MAPPING".equals(params.path("sensing_type").asText()))mapping.requireSupported(step);
        if(step.getStepType()==StepType.EXECUTE_SENSING && com.mediassistrover.backend.ai.AiSensingService.TYPES.contains(params.path("sensing_type").asText()))ai.requireEnabled();
        if (step.getStepType()==StepType.EXECUTE_SENSING && !Set.of("ROBOT_HEALTH","SIDE_CLEARANCE","MAPPING","CROWD_DENSITY","RESTRICTED_ZONE").contains(params.path("sensing_type").asText()))
            throw new BusinessRuleException("Sensing action is outside the integrated Pi contract; keep workflow DRAFT");
        if (step.getStepType()==StepType.INTERACT && (!"SPEAK".equals(params.path("action").asText()) || step.getInputMappingJson().has("action")))
            throw new BusinessRuleException("Only fixed INTERACT/SPEAK is executed by Pi");
        if (step.getStepType()==StepType.WAIT && !"DURATION".equals(params.path("wait_type").asText()))
            throw new BusinessRuleException("Only WAIT/DURATION is executed by Pi");
    }

    @Transactional
    public void dispatch(JobStep step) {
        if(!step.isMappingCleanup() && !rollout.dispatchAllowed())return;
        if (!properties.isEnabled() || !properties.isDispatchEnabled() && !step.isMappingCleanup()) return;
        Job job=step.getJobTask().getJob();
        if (!job.isBeOrchestrated() || job.getStatus()!=JobStatus.RUNNING || job.getSelectedJobStep()==null
                || !job.getSelectedJobStep().getJobStepId().equals(step.getJobStepId()) || step.getStatus()!=JobStepStatus.PENDING
                || executionPolicy.isServer(step,step.getResolvedInputsJson())) throw new BusinessRuleException("Dispatch requires selected PENDING Pi step");
        if(step.getInterruptionReason()!=null || job.getCancelRequestedAt()!=null && !step.isMappingCleanup())return;
        // Configuration/catalog changes after commit must not reinterpret an existing operation.
        if (commands.findByJobStepId(step.getJobStepId()).isPresent()) return;
        if(mapping.open(job.getJobId()) && !com.mediassistrover.backend.mapping.MappingLifecycleService.mapping(step))
            throw new BusinessRuleException("Open mapping permits only MAPPING/SAVE or STOP Pi actions");
        boolean unlock="CONTROL_HARDWARE".equals(step.getStepTypeSnapshot())
                && "UNLOCK_COMPARTMENT".equals(step.getResolvedInputsJson().path("action").asText());
        if(unlock && !properties.isAllowCommissioningUnlock()) operationAuthorization.requireBound(step,step.getResolvedInputsJson().path("authorization"));
        if(!step.isMappingCleanup())requireActionSupported(step.getStep());
        if(com.mediassistrover.backend.mapping.MappingLifecycleService.mapping(step))mapping.requireBound(step);
        ObjectNode business=((ObjectNode)step.getResolvedInputsJson()).deepCopy();
        if(unlock) business.remove("authorization");
        scope.validate(step.getStep(),business,job);
        if (job.getRobot()==null || !properties.manages(job.getRobot().getRobotId())) throw new BusinessRuleException("Robot is not enrolled for MQTT");
        robots.findByIdForUpdate(job.getRobot().getRobotId()).orElseThrow();
        RobotMqttSession session=sessions.lock(job.getRobot().getRobotId()).orElse(null);
        if (!registry.fresh(session)) return;
        if(unlock && properties.isAllowCommissioningUnlock() && !"COMMISSIONING_ONLY".equals(session.getHelloJson().path("unlockPolicy").asText()))
            throw new BusinessRuleException("Commissioning UNLOCK must be enabled on both BE and Pi");
        if(com.mediassistrover.backend.mapping.MappingLifecycleService.mapping(step) && !session.getHelloJson().path("mappingEnabled").asBoolean())
            throw new BusinessRuleException("Pi does not enable mapping");
        if (session.getHelloJson().path("simulation").asBoolean() && !properties.isAllowSimulation()) throw new BusinessRuleException("Simulation dispatch is disabled");
        boolean enabled=false;for (var type:session.getHelloJson().path("enabledStepTypes")) if (step.getStepTypeSnapshot().equals(type.asText())) enabled=true;
        if (!enabled) throw new BusinessRuleException("Pi does not enable selected StepType");
        if ("MOVE".equals(step.getStepTypeSnapshot()) && (job.getMap()==null || job.getRobot().getCurrentMap()==null
                || !job.getMap().getMapId().equals(job.getRobot().getCurrentMap().getMapId()) || job.getMap().getAssetHash()==null
                || !job.getMap().getAssetHash().equalsIgnoreCase(Objects.toString(job.getRobot().getMapAssetHash(),""))))
            throw new BusinessRuleException("MOVE requires matching map UUID and asset hash");
        String profileHash=null;
        com.fasterxml.jackson.databind.JsonNode aiProfile=com.mediassistrover.backend.ai.AiSensingService.ai(step)?ai.bind(step):null;
        if("MOVE".equals(step.getStepTypeSnapshot())) {
            profileHash=navigationProfiles.ready(job.getRobot(),session,step.getResolvedInputsJson());
            if(profileHash==null) return;
        }
        if (!commands.findByRobotIdAndStateIn(session.getRobotId(),List.of("PENDING","PUBLISHING","PUBLISHED","ACKNOWLEDGED","STARTED","RECOVERY_REQUIRED")).isEmpty())
            throw new BusinessRuleException("Robot already has an unresolved Pi command");
        ObjectNode params=((ObjectNode)step.getResolvedInputsJson()).deepCopy();
        // Metadata permitted in BE templates is never sent as GPIO/authorization or unrelated business data.
        if ("CONTROL_HARDWARE".equals(step.getStepTypeSnapshot())) {params.remove(List.of("maxAttempts","service_request_id"));
            if ("LOCK".equals(params.path("action").asText())) params.put("action","LOCK_COMPARTMENT");}
        inputs.validatePiParams(step.getStep(),params);
        UUID commandId=UUID.randomUUID();Instant now=Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        ObjectNode command=wire.object().put("protocolVersion","1.0").put("contract","MAR-STEP-2").put("messageType","COMMAND")
                .put("commandId",commandId.toString()).put("correlationId",commandId.toString()).put("robotId",session.getRobotId().toString())
                .put("transportSessionId",session.getTransportSessionId().toString()).put("commandType","EXECUTE_STEP")
                .put("expiresAt",now.plusSeconds(properties.getCommandTtlSec()).toString());
        ObjectNode action=command.putObject("step").put("jobId",job.getJobId().toString()).put("jobStepId",step.getJobStepId().toString())
                .put("stepId",step.getStep().getStepId().toString()).put("stepCode",step.getStepCodeSnapshot()).put("stepType",step.getStepTypeSnapshot())
                .put("visitNo",step.getVisitNo()).put("operationKey",step.getOperationKey()).put("timeoutSec",step.getTimeoutSecSnapshot());action.set("params",params);
        if("MOVE".equals(step.getStepTypeSnapshot())){
            var context=navigationSessions.commandContext(session.getRobotId(),profileHash);
            if(context!=null)command.set("navigationContext",context);
        }
        contracts.command(command);
        commands.save(RobotCommandOutbox.builder().commandId(commandId).robotId(session.getRobotId()).jobId(job.getJobId()).jobStepId(step.getJobStepId())
                .operationKey(step.getOperationKey()).navigationProfileHash(profileHash).aiProfileSnapshotJson(aiProfile).payloadJson(command).expiresAt(now.plusSeconds(properties.getCommandTtlSec())).state("PENDING").createdAt(now).build());
    }
}
