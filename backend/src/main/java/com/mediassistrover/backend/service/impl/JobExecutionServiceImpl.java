package com.mediassistrover.backend.service.impl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.JobExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor @Transactional
public class JobExecutionServiceImpl implements JobExecutionService {
    private static final Set<JobStatus> ACTIVE=EnumSet.of(JobStatus.QUEUED,JobStatus.ACCEPTED,JobStatus.RUNNING,JobStatus.RECOVERY_REQUIRED);
    private static final Set<ServiceRequestType> ROBOT_PINNED=EnumSet.of(ServiceRequestType.CROWD_MONITORING,
            ServiceRequestType.FALL_DETECTION_RESPONSE,ServiceRequestType.RESTRICTED_AREA_MONITORING,
            ServiceRequestType.AUTO_CHARGING,ServiceRequestType.NAVIGATION_RECOVERY,ServiceRequestType.ROBOT_STARTUP_STANDBY);
    private final WorkflowRepository workflowRepository; private final TaskRepository taskRepository;
    private final StepRepository stepRepository; private final JobRepository jobRepository;
    private final JobTaskRepository jobTaskRepository; private final JobStepRepository jobStepRepository;
    private final JobEventRepository eventRepository; private final RobotRepository robotRepository;
    private final NavigationRequestRepository navigationRepository; private final EscortRequestRepository escortRepository;
    private final DeliveryRequestRepository deliveryRepository;
    private final FacilityMapRepository mapRepository;
    private final ObjectMapper objectMapper; private final Clock clock;
    private final com.mediassistrover.backend.mqtt.MqttProperties mqttProperties;
    private final RequestJobInputSnapshotFactory inputSnapshots;
    private final com.mediassistrover.backend.validation.WorkflowExecutionReadiness readiness;
    private final com.mediassistrover.backend.service.OrchestrationWorkService wakeups;
    private final FloorRepository floors;
    private final MappingSessionRepository mappingSessions;
    private final com.mediassistrover.backend.mqtt.MqttRolloutService rollout;
    private final com.mediassistrover.backend.service.NavigationSessionService navigationSessions;
    @Override public Job createForSpeech(UUID workflowId,UUID robotId,String text,String languageCode) {
        var policy=rollout.lockForRuntimeCreation();
        Workflow workflow=workflowRepository.findById(workflowId).orElseThrow(()->new ResourceNotFoundException("Workflow not found"));
        rollout.requireSchema(policy,workflow.getSchemaVersion());
        if(workflow.getSchemaVersion()!=3 || !"SPEECH".equals(workflow.getServiceType()) || workflow.getStatus()!=WorkflowStatus.PUBLISHED
                || !workflow.isAcceptsNewJobs() || workflow.getDefinitionHash()==null)throw new BusinessRuleException("Speech requires a published schema 3 SPEECH workflow");
        var actions=stepRepository.findByWorkflowWorkflowIdOrderBySequenceNoAsc(workflowId).stream().filter(Step::isEnabled).toList();
        if(actions.isEmpty() || actions.stream().noneMatch(s->s.getStepType()==StepType.INTERACT)
                || actions.stream().anyMatch(s->s.getStepType()!=StepType.INTERACT || !"SPEAK".equals(s.getParamsJson().path("action").asText())))
            throw new BusinessRuleException("Speech jobs accept only INTERACT/SPEAK steps");
        if(text==null || text.isBlank() || text.length()>500 || !Set.of("vi","en").contains(Objects.toString(languageCode,"")))
            throw new BusinessRuleException("Speech requires 1-500 characters and languageCode vi or en");
        Robot robot=selectPinnedRobot(robotRepository.findById(robotId).orElseThrow(()->new ResourceNotFoundException("Robot not found")));
        if(!mappingSessions.findByRobotIdAndPotentiallyOpenTrue(robotId).isEmpty())throw new BusinessRuleException("Robot holds an open mapping session");
        var input=merge(workflow.getDefaultInputsJson(),objectMapper.createObjectNode().put("speech_text",text).put("language_code",languageCode));
        return create(workflow,null,null,robot,null,1,input,null,null);
    }

    @Override public Job createForMapping(UUID workflowId,UUID robotId,UUID floorId,String mapName) {
        rollout.lockForRuntimeCreation();
        Workflow workflow=workflowRepository.findById(workflowId).orElseThrow(()->new ResourceNotFoundException("Workflow not found"));
        if(workflow.getSchemaVersion()!=3 || !"MAPPING".equals(workflow.getServiceType()) || workflow.getStatus()!=WorkflowStatus.PUBLISHED
                || !workflow.isAcceptsNewJobs() || workflow.getDefinitionHash()==null)throw new BusinessRuleException("Mapping requires a published schema 3 MAPPING workflow");
        if(mapName==null || mapName.isBlank() || mapName.length()>160 || floorId==null)throw new BusinessRuleException("Mapping requires floor_id and map_name (1-160 characters)");
        if(!floors.findById(floorId).map(Floor::isActive).orElse(false))throw new BusinessRuleException("Mapping requires an active floor");
        Robot robot=selectPinnedRobot(robotRepository.findById(robotId).orElseThrow(()->new ResourceNotFoundException("Robot not found")));
        if(!mappingSessions.findByRobotIdAndPotentiallyOpenTrue(robotId).isEmpty())throw new BusinessRuleException("Robot holds an open mapping session");
        return create(workflow,null,null,robot,null,1,objectMapper.createObjectNode().put("floor_id",floorId.toString()).put("map_name",mapName),null,null);
    }

    @Override public Job createForRequest(ServiceRequest request){var policy=rollout.lockForRuntimeCreation();Workflow workflow=workflowFor(request,policy);rollout.requireSchema(policy,workflow.getSchemaVersion());FacilityMap map=mapFor(request);
        boolean sessionPinned=request.getRequestDataJson().hasNonNull("navigation_session_id");
        if(sessionPinned){if(request.getRequestedRobot()==null||map==null)throw new BusinessRuleException("Navigation session requires its selected robot/map");
            navigationSessions.requireReadyContext(request.getRequestedRobot().getRobotId(),UUID.fromString(request.getRequestDataJson().path("navigation_session_id").asText()),map.getMapId());}
        Robot robot=ROBOT_PINNED.contains(request.getRequestType())||sessionPinned?selectPinnedRobot(request.getRequestedRobot()):selectRobot(request.getRequestedRobot(),map,workflow);JsonNode input=inputSnapshots.forRequest(workflow,request);Job job=create(workflow,request,null,robot,map,request.getPriority(),input,null,null);job.setRecoveryOfJob(recoverySource(request));return job;}
    @Override public Job createForSchedule(Schedule schedule,Instant fireAt,JsonNode input){var policy=rollout.lockForRuntimeCreation();if(jobRepository.existsByScheduleScheduleIdAndScheduleFireAtAndScheduleItemKey(schedule.getScheduleId(),fireAt,"DEFAULT"))return jobRepository.findAll().stream().filter(j->j.getSchedule()!=null&&j.getSchedule().getScheduleId().equals(schedule.getScheduleId())&&fireAt.equals(j.getScheduleFireAt())).findFirst().orElseThrow();Workflow w=schedule.getWorkflow();rollout.requireSchema(policy,w.getSchemaVersion());if(w.getStatus()!=WorkflowStatus.PUBLISHED||!w.isAcceptsNewJobs())throw new BusinessRuleException("Schedule workflow does not accept new jobs");Robot robot=selectRobot(schedule.getRobot(),null,w);return create(w,null,schedule,robot,robot==null?null:robot.getCurrentMap(),schedule.getPriority(),merge(w.getDefaultInputsJson(),input),fireAt,"DEFAULT");}

    private Job create(Workflow workflow,ServiceRequest request,Schedule schedule,Robot robot,FacilityMap map,int priority,JsonNode input,Instant fireAt,String itemKey){
        if(robot!=null && !readiness.acceptsRobot(workflow,robot))throw new BusinessRuleException("Selected robot does not support this workflow transport; enrolled MQTT robots require schema 3");
        readiness.requireReady(workflow);if(robot!=null && workflow.getSchemaVersion()==2 && mqttProperties.manages(robot.getRobotId()))throw new BusinessRuleException("Enrolled MQTT robots require a schema 3 workflow; do not switch active Job transport");Instant now=Instant.now(clock);Job job=jobRepository.saveAndFlush(Job.builder().workflow(workflow).serviceRequest(request).schedule(schedule).robot(robot).map(map).assignedStaffAccount(request!=null&&request.getRequestedByAccount()!=null&&request.getRequestedByAccount().getRole()==Role.STAFF?request.getRequestedByAccount():null).jobCode("JOB-"+UUID.randomUUID().toString().replace("-","").substring(0,20).toUpperCase(Locale.ROOT)).status(JobStatus.QUEUED).priority(priority).inputJson(input).contextJson(objectMapper.createObjectNode()).workflowHash(workflow.getDefinitionHash()).protocolVersion(1).schemaVersion(workflow.getSchemaVersion()).graphVersion(workflow.getGraphVersion()).beOrchestrated(workflow.getSchemaVersion()==3).scheduleFireAt(fireAt).scheduleItemKey(itemKey).dispatchCommandId(robot==null||workflow.getSchemaVersion()==3?null:UUID.randomUUID()).dispatchExpiresAt(robot==null||workflow.getSchemaVersion()==3?null:now.plusSeconds(60)).queuedAt(now).scheduledAt(fireAt).build());
        Map<UUID,JobTask> runtimeTasks=new HashMap<>();for(Task task:taskRepository.findByWorkflowWorkflowIdOrderBySequenceNoAsc(workflow.getWorkflowId())){JobTask jt=jobTaskRepository.save(JobTask.builder().job(job).task(task).sequenceNo(task.getSequenceNo()).taskNameSnapshot(task.getTaskName()).configSnapshotJson(copy(task.getConfigJson())).status(JobTaskStatus.PENDING).build());runtimeTasks.put(task.getTaskId(),jt);}jobTaskRepository.flush();
        long seq=0;for(Step step:stepRepository.findByWorkflowWorkflowIdOrderBySequenceNoAsc(workflow.getWorkflowId())){if(!step.isEnabled())continue;jobStepRepository.save(JobStep.builder().jobTask(runtimeTasks.get(step.getTask().getTaskId())).step(step).visitNo(1).executionSequenceNo(++seq).stepCodeSnapshot(step.getStepCode()).stepTypeSnapshot(step.getStepType().getStepTypeCode()).resolvedInputsJson(objectMapper.createObjectNode()).status(JobStepStatus.PENDING).attemptNo(1).operationKey(job.getJobId()+":"+step.getStepCode()+":1").timeoutSecSnapshot(step.getTimeoutSec()).build());}
        event(job,"JOB_QUEUED",null,JobStatus.QUEUED.name(),objectMapper.createObjectNode());wakeups.request(job);return job;
    }
    private Workflow workflowFor(ServiceRequest request,com.mediassistrover.backend.mqtt.MqttRolloutService.State policy){ServiceRequestType type=request.getRequestType();String serviceType=switch(type){case INFORMATION->"INFORMATION";case NAVIGATION->"NAVIGATION";case ESCORT->"ESCORT";case DELIVERY->"DELIVERY";case EMERGENCY_SOS->"EMERGENCY";case CROWD_MONITORING->"CROWD_MONITORING";case FALL_DETECTION_RESPONSE->"FALL_DETECTION_RESPONSE";case RESTRICTED_AREA_MONITORING->"RESTRICTED_AREA_MONITORING";case AUTO_CHARGING->"AUTO_CHARGING";case NAVIGATION_RECOVERY->"NAVIGATION_RECOVERY";case ROBOT_STARTUP_STANDBY->"ROBOT_STARTUP_STANDBY";};Workflow selected=type==ServiceRequestType.NAVIGATION?navigationRepository.findById(request.getServiceRequestId()).map(NavigationRequest::getWorkflow).orElse(null):null;if(selected!=null){if(selected.getStatus()!=WorkflowStatus.PUBLISHED||!selected.isAcceptsNewJobs()||!serviceType.equalsIgnoreCase(selected.getServiceType()))throw new BusinessRuleException("Selected workflow is not compatible with request type "+type);return selected;}return (policy.mode()==com.mediassistrover.backend.mqtt.MqttRolloutService.Mode.LEGACY_COMPATIBLE?workflowRepository.findFirstByServiceTypeIgnoreCaseAndStatusAndAcceptsNewJobsTrueOrderByVersionNoDesc(serviceType,WorkflowStatus.PUBLISHED):workflowRepository.findFirstByServiceTypeIgnoreCaseAndStatusAndAcceptsNewJobsTrueAndSchemaVersionOrderByVersionNoDesc(serviceType,WorkflowStatus.PUBLISHED,3)).orElseThrow(()->new BusinessRuleException("No published workflow accepts request type "+type));}
    private Robot selectRobot(Robot preferred,FacilityMap map,Workflow workflow){
        if(preferred!=null){
            Robot locked=robotRepository.findByIdForUpdate(preferred.getRobotId()).orElseThrow(()->new ResourceNotFoundException("Robot not found: "+preferred.getRobotId()));
            if(!readiness.acceptsRobot(workflow,locked))throw new BusinessRuleException("Selected robot does not support this workflow transport");
            if(locked.getStatus()==RobotStatus.IDLE && (map==null || locked.getCurrentMap()!=null && map.getMapId().equals(locked.getCurrentMap().getMapId())) && jobRepository.findByRobotRobotIdAndStatusIn(locked.getRobotId(),ACTIVE).isEmpty())return locked;
        }
        List<Robot> candidates=map==null?robotRepository.findByStatusOrderByRobotCodeAsc(RobotStatus.IDLE):robotRepository.findByStatusAndCurrentMapMapIdOrderByRobotCodeAsc(RobotStatus.IDLE,map.getMapId());
        return candidates.stream().filter(r->readiness.acceptsRobot(workflow,r))
                .map(r->robotRepository.findByIdForUpdate(r.getRobotId()).orElseThrow())
                .filter(r->r.getStatus()==RobotStatus.IDLE && jobRepository.findByRobotRobotIdAndStatusIn(r.getRobotId(),ACTIVE).isEmpty()).findFirst().orElse(null);
    }
    private Robot selectPinnedRobot(Robot requested){
        if(requested==null)throw new BusinessRuleException("Operational request requires a robot");
        Robot locked=robotRepository.findByIdForUpdate(requested.getRobotId()).orElseThrow(()->new ResourceNotFoundException("Robot not found: "+requested.getRobotId()));
        if(locked.getStatus()!=RobotStatus.IDLE)throw new BusinessRuleException("Operational request requires the selected robot to be IDLE");
        ensureNoActive(locked);return locked;
    }
    private void ensureNoActive(Robot robot){if(!jobRepository.findByRobotRobotIdAndStatusIn(robot.getRobotId(),ACTIVE).isEmpty())throw new ConflictException("Robot already has an active or unreconciled Job");}
    private FacilityMap mapFor(ServiceRequest r){return switch(r.getRequestType()){case NAVIGATION->navigationRepository.findById(r.getServiceRequestId()).map(NavigationRequest::getMap).orElse(null);case ESCORT->escortRepository.findById(r.getServiceRequestId()).map(EscortRequest::getMap).orElse(null);case DELIVERY->deliveryRepository.findById(r.getServiceRequestId()).map(DeliveryRequest::getMap).orElse(null);case CROWD_MONITORING,RESTRICTED_AREA_MONITORING,NAVIGATION_RECOVERY->mapFromData(r.getRequestDataJson());case EMERGENCY_SOS,INFORMATION,FALL_DETECTION_RESPONSE,AUTO_CHARGING,ROBOT_STARTUP_STANDBY->null;};}
    private FacilityMap mapFromData(JsonNode data){
        if(data==null||!data.path("map_id").isTextual())return null;
        try{return mapRepository.findById(UUID.fromString(data.path("map_id").asText())).orElseThrow(()->new ResourceNotFoundException("Map not found"));}
        catch(IllegalArgumentException ex){throw new BusinessRuleException("map_id must be a UUID");}
    }
    private Job recoverySource(ServiceRequest request){
        if(request.getRequestType()!=ServiceRequestType.NAVIGATION_RECOVERY)return null;
        JsonNode value=request.getRequestDataJson()==null?null:request.getRequestDataJson().get("recovery_of_job_id");
        if(value==null||!value.isTextual())throw new BusinessRuleException("recovery_of_job_id is required");
        try{return jobRepository.findById(UUID.fromString(value.asText())).orElseThrow(()->new ResourceNotFoundException("Recovery source Job not found"));}
        catch(IllegalArgumentException ex){throw new BusinessRuleException("recovery_of_job_id must be a UUID");}
    }
    private JsonNode merge(JsonNode defaults,JsonNode runtime){return inputSnapshots.merged(defaults,runtime);}
    private JsonNode copy(JsonNode n){return n==null?objectMapper.createObjectNode():n.deepCopy();}
    private void event(Job job,String type,String previous,String next,JsonNode data){Instant now=Instant.now(clock);eventRepository.save(JobEvent.builder().jobEventId(UUID.randomUUID()).job(job).robot(job.getRobot()).producerType(JobEventProducerType.SERVER).producerSessionId(UUID.randomUUID()).sequenceNo(1).eventType(type).previousStatus(previous).newStatus(next).statusScope("JOB").eventDataJson(data).occurredAt(now).receivedAt(now).persistedAt(now).build());}
}
