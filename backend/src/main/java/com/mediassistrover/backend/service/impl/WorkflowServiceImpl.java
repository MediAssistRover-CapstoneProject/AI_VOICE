package com.mediassistrover.backend.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.dto.workflow.request.WorkflowRequests.*;
import com.mediassistrover.backend.dto.workflow.response.WorkflowResponses.*;
import com.mediassistrover.backend.entity.Account;
import com.mediassistrover.backend.entity.Step;
import com.mediassistrover.backend.entity.StepTransition;
import com.mediassistrover.backend.enums.StepType;
import com.mediassistrover.backend.entity.Task;
import com.mediassistrover.backend.entity.Workflow;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.*;
import com.mediassistrover.backend.mapper.WorkflowMapper;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class WorkflowServiceImpl implements WorkflowService {
    private final WorkflowRepository workflowRepository;
    private final TaskRepository taskRepository; private final StepRepository stepRepository;
    private final StepTransitionRepository transitionRepository; private final AccountRepository accountRepository;
    private final WorkflowMapper mapper; private final WorkflowValidationService validationService;
    private final AuditService auditService; private final Clock clock;
    private final JobRepository jobRepository;
    private final com.mediassistrover.backend.validation.WorkflowExecutionReadiness readiness;
    private final com.mediassistrover.backend.mqtt.MqttRolloutService rollout;

    @Override @Transactional
    public WorkflowDetailResponse createGraph(GraphCreateRequest request,UUID actorId,RequestMetadata md){
        WorkflowResponse created=create(request.workflow(),actorId,md);
        return replaceGraph(created.workflowId(),request.graph(),actorId,md);
    }
    @Override @Transactional
    public WorkflowDetailResponse replaceGraph(UUID id,GraphRequest r,UUID actorId,RequestMetadata md){
        Workflow w=editable(id);
        if(w.getStatus()!=WorkflowStatus.DRAFT)throw new BusinessRuleException("Graph replacement requires DRAFT workflow");
        if(jobRepository.existsByWorkflowWorkflowId(id))throw new ConflictException("Workflow is referenced by Job history");
        w.setStartStep(null);workflowRepository.flush();
        transitionRepository.deleteAll(transitionRepository.findByWorkflowWorkflowIdOrderByFromStepSequenceNoAscOutcomeAscPriorityAsc(id));transitionRepository.flush();
        stepRepository.deleteAll(stepRepository.findByWorkflowWorkflowIdOrderBySequenceNoAsc(id));stepRepository.flush();
        taskRepository.deleteAll(taskRepository.findByWorkflowWorkflowIdOrderBySequenceNoAsc(id));taskRepository.flush();
        Map<String,Task> tasks=new HashMap<>();Set<Integer> taskOrders=new HashSet<>();
        for(TaskRequest t:r.tasks()){
            if(tasks.containsKey(t.taskCode())||!taskOrders.add(t.sequenceNo()))throw new BusinessRuleException("Duplicate task code/order");
            Task entity=taskRepository.save(Task.builder().workflow(w).taskCode(t.taskCode()).taskName(t.taskName()).sequenceNo(t.sequenceNo()).description(t.description()).configJson(t.configJson()).build());
            tasks.put(t.taskCode(),entity);
        }
        taskRepository.flush();Map<String,Step> steps=new HashMap<>();Set<Integer> stepOrders=new HashSet<>();
        for(GraphStepRequest s:r.steps()){
            if(steps.containsKey(s.stepCode())||!stepOrders.add(s.sequenceNo()))throw new BusinessRuleException("Duplicate step code/order");
            Task task=tasks.get(s.taskCode());if(task==null)throw new BusinessRuleException("Unknown task: "+s.taskCode());
            Step entity=stepRepository.save(Step.builder().workflow(w).task(task).stepType(s.stepType()).stepCode(s.stepCode()).stepName(s.stepName()).sequenceNo(s.sequenceNo()).description(s.description()).enabled(true).paramsJson(s.paramsJson()).inputMappingJson(s.inputMappingJson()).timeoutSec(s.timeoutSec()).maxAttempts(s.maxAttempts()).retryDelaySec(s.retryDelaySec()).retryCodesJson(s.retryCodesJson()).maxVisits(s.maxVisits()).auditLevel(s.auditLevel()).build());
            steps.put(s.stepCode(),entity);
        }
        stepRepository.flush();Step start=steps.get(r.startStepCode());if(start==null)throw new BusinessRuleException("Unknown start step");w.setStartStep(start);
        Set<String> routes=new HashSet<>();
        for(GraphTransitionRequest t:r.transitions()){
            Step from=steps.get(t.fromStepCode()),to=t.toStepCode()==null?null:steps.get(t.toStepCode());
            if(from==null||t.toStepCode()!=null&&to==null)throw new BusinessRuleException("Unknown transition source/target");
            if(!routes.add(t.fromStepCode()+":"+t.outcome()+":"+t.priority()))throw new BusinessRuleException("Duplicate transition priority");
            transitionRepository.save(StepTransition.builder().workflow(w).fromStep(from).toStep(to).outcome(t.outcome()).conditionJson(nullableJson(t.conditionJson())).priority(t.priority()).defaultTransition(t.defaultTransition()).terminalStatus(t.terminalStatus()).build());
        }
        transitionRepository.flush();dirty(w);validationService.validateAndCompile(w);
        audit(actorId,"WORKFLOW_GRAPH_REPLACED","Workflow",id,md);return detail(w);
    }

    @Override public List<StepTypeResponse> stepTypes(){return Arrays.stream(StepType.values()).map(t -> new StepTypeResponse(t.name(),t.getDisplayName(),t.getExecutorKind())).toList();}
    @Override @Transactional public WorkflowResponse create(WorkflowUpsertRequest r,UUID actorId,RequestMetadata md){if(workflowRepository.existsByWorkflowCodeIgnoreCaseAndVersionNo(r.workflowCode(),r.versionNo()))throw new ConflictException("Workflow code/version already exists");Workflow w=mapper.toEntity(r);w.setStatus(WorkflowStatus.DRAFT);w.setAcceptsNewJobs(false);w=workflowRepository.save(w);audit(actorId,"WORKFLOW_CREATED","Workflow",w.getWorkflowId(),md);return mapper.toResponse(w);}
    @Override @Transactional public WorkflowResponse update(UUID id,WorkflowUpsertRequest r,UUID actorId,RequestMetadata md){Workflow w=editable(id);if(!w.getWorkflowCode().equals(r.workflowCode())||w.getVersionNo()!=r.versionNo())throw new ConflictException("Workflow code and version are immutable");mapper.update(r,w);dirty(w);audit(actorId,"WORKFLOW_UPDATED","Workflow",id,md);return mapper.toResponse(w);}
    @Override public WorkflowDetailResponse get(UUID id){Workflow w=workflow(id);return detail(w);}
    @Override public Page<WorkflowResponse> list(WorkflowStatus status,Pageable p){return(status==null?workflowRepository.findAll(p):workflowRepository.findByStatus(status,p)).map(mapper::toResponse);}
    @Override public List<WorkflowDetailResponse> published(){return workflowRepository.findByStatusAndAcceptsNewJobsTrueOrderByWorkflowCodeAscVersionNoDesc(WorkflowStatus.PUBLISHED).stream().map(this::detail).toList();}
    @Override public Page<PublishedWorkflowSummaryResponse> published(String serviceType,Pageable p){String normalized=serviceType==null||serviceType.isBlank()?null:serviceType.trim();return workflowRepository.searchPublished(WorkflowStatus.PUBLISHED,normalized,p).map(this::publishedSummary);}
    @Override public WorkflowDetailResponse published(UUID id){Workflow w=workflow(id);if(w.getStatus()!=WorkflowStatus.PUBLISHED||!w.isAcceptsNewJobs())throw new ResourceNotFoundException("Published workflow not found: "+id);return detail(w);}

    @Override @Transactional public TaskResponse addTask(UUID wid,TaskRequest r,UUID actorId,RequestMetadata md){Workflow w=editable(wid);if(taskRepository.existsByWorkflowWorkflowIdAndTaskCodeIgnoreCase(wid,r.taskCode())||taskRepository.existsByWorkflowWorkflowIdAndSequenceNo(wid,r.sequenceNo()))throw new ConflictException("Task code or sequence already exists");Task t=taskRepository.save(Task.builder().workflow(w).taskCode(r.taskCode()).taskName(r.taskName()).sequenceNo(r.sequenceNo()).description(r.description()).configJson(r.configJson()).build());dirty(w);audit(actorId,"WORKFLOW_TASK_CREATED","Task",t.getTaskId(),md);return mapper.toResponse(t);}
    @Override @Transactional public TaskResponse updateTask(UUID wid,UUID id,TaskRequest r,UUID actorId,RequestMetadata md){Workflow w=editable(wid);Task t=task(wid,id);t.setTaskCode(r.taskCode());t.setTaskName(r.taskName());t.setSequenceNo(r.sequenceNo());t.setDescription(r.description());t.setConfigJson(r.configJson());dirty(w);audit(actorId,"WORKFLOW_TASK_UPDATED","Task",id,md);return mapper.toResponse(t);}
    @Override @Transactional public void deleteTask(UUID wid,UUID id,UUID actorId,RequestMetadata md){Workflow w=editable(wid);Task t=task(wid,id);if(stepRepository.findByWorkflowWorkflowIdOrderBySequenceNoAsc(wid).stream().anyMatch(s->s.getTask().getTaskId().equals(id)))throw new ConflictException("Task still contains steps");taskRepository.delete(t);dirty(w);audit(actorId,"WORKFLOW_TASK_DELETED","Task",id,md);}

    @Override @Transactional public StepResponse addStep(UUID wid,StepRequest r,UUID actorId,RequestMetadata md){Workflow w=editable(wid);Task t=task(wid,r.taskId());StepType type=stepType(r.stepTypeCode());if(stepRepository.existsByWorkflowWorkflowIdAndStepCodeIgnoreCase(wid,r.stepCode())||stepRepository.existsByWorkflowWorkflowIdAndSequenceNo(wid,r.sequenceNo()))throw new ConflictException("Step code or sequence already exists");Step s=stepRepository.save(stepFrom(r,w,t,type));dirty(w);audit(actorId,"WORKFLOW_STEP_CREATED","Step",s.getStepId(),md);return mapper.toResponse(s);}
    @Override @Transactional public StepResponse updateStep(UUID wid,UUID id,StepRequest r,UUID actorId,RequestMetadata md){Workflow w=editable(wid);Step s=step(wid,id);Task t=task(wid,r.taskId());StepType type=stepType(r.stepTypeCode());apply(r,s,t,type);dirty(w);audit(actorId,"WORKFLOW_STEP_UPDATED","Step",id,md);return mapper.toResponse(s);}
    @Override @Transactional public void deleteStep(UUID wid,UUID id,UUID actorId,RequestMetadata md){Workflow w=editable(wid);Step s=step(wid,id);if(w.getStartStep()!=null&&w.getStartStep().getStepId().equals(id))throw new ConflictException("Start step cannot be deleted");if(transitionRepository.existsByFromStepStepIdOrToStepStepId(id,id))throw new ConflictException("Step is referenced by transitions");stepRepository.delete(s);dirty(w);audit(actorId,"WORKFLOW_STEP_DELETED","Step",id,md);}

    @Override @Transactional public TransitionResponse addTransition(UUID wid,TransitionRequest r,UUID actorId,RequestMetadata md){Workflow w=editable(wid);validateTransitionRequest(r);StepTransition t=transitionRepository.save(transitionFrom(wid,r,w));dirty(w);audit(actorId,"WORKFLOW_TRANSITION_CREATED","StepTransition",t.getTransitionId(),md);return mapper.toResponse(t);}
    @Override @Transactional public TransitionResponse updateTransition(UUID wid,UUID id,TransitionRequest r,UUID actorId,RequestMetadata md){Workflow w=editable(wid);validateTransitionRequest(r);StepTransition t=transition(wid,id);apply(r,t,wid);dirty(w);audit(actorId,"WORKFLOW_TRANSITION_UPDATED","StepTransition",id,md);return mapper.toResponse(t);}
    @Override @Transactional public void deleteTransition(UUID wid,UUID id,UUID actorId,RequestMetadata md){Workflow w=editable(wid);transitionRepository.delete(transition(wid,id));dirty(w);audit(actorId,"WORKFLOW_TRANSITION_DELETED","StepTransition",id,md);}
    @Override @Transactional public WorkflowResponse setStartStep(UUID wid,UUID sid,UUID actorId,RequestMetadata md){Workflow w=editable(wid);w.setStartStep(step(wid,sid));dirty(w);audit(actorId,"WORKFLOW_START_STEP_SET","Workflow",wid,md);return mapper.toResponse(w);}

    @Override @Transactional public WorkflowValidationResponse validate(UUID id,UUID actorId,RequestMetadata md){Workflow w=editable(id);WorkflowValidationService.CompilationResult result=validationService.validateAndCompile(w);w.setCompiledDefinitionJson(result.compiledDefinition());w.setDefinitionHash(result.definitionHash());w.setStatus(WorkflowStatus.VALIDATED);w.setAcceptsNewJobs(false);audit(actorId,"WORKFLOW_VALIDATED","Workflow",id,md);return new WorkflowValidationResponse(id,w.getStatus(),result.definitionHash(),result.taskCount(),result.stepCount(),result.transitionCount(),result.warnings());}
    @Override @Transactional public WorkflowResponse publish(UUID id,UUID actorId,RequestMetadata md){var policy=rollout.lockForRuntimeCreation();Workflow w=workflow(id);rollout.requireSchema(policy,w.getSchemaVersion());readiness.requireReady(w);if(w.getStatus()!=WorkflowStatus.VALIDATED)throw new BusinessRuleException("Only VALIDATED workflow can be published");WorkflowValidationService.CompilationResult current=validationService.validateAndCompile(w);if(!Objects.equals(w.getDefinitionHash(),current.definitionHash()))throw new ConflictException("Workflow changed after validation");w.setStatus(WorkflowStatus.PUBLISHED);w.setAcceptsNewJobs(true);w.setPublishedAt(Instant.now(clock));w.setPublishedByAccount(account(actorId));audit(actorId,"WORKFLOW_PUBLISHED","Workflow",id,md);return mapper.toResponse(w);}
    @Override @Transactional public WorkflowResponse archive(UUID id,UUID actorId,RequestMetadata md){Workflow w=workflow(id);if(w.getStatus()!=WorkflowStatus.PUBLISHED)throw new BusinessRuleException("Only PUBLISHED workflow can be archived");w.setStatus(WorkflowStatus.ARCHIVED);w.setAcceptsNewJobs(false);audit(actorId,"WORKFLOW_ARCHIVED","Workflow",id,md);return mapper.toResponse(w);}
    @Override @Transactional public WorkflowDetailResponse cloneVersion(UUID id,String name,UUID actorId,RequestMetadata md){Workflow source=workflow(id);if(source.getStatus()!=WorkflowStatus.PUBLISHED&&source.getStatus()!=WorkflowStatus.ARCHIVED)throw new BusinessRuleException("Only PUBLISHED or ARCHIVED workflow can be cloned");int version=workflowRepository.findTopByWorkflowCodeIgnoreCaseOrderByVersionNoDesc(source.getWorkflowCode()).map(x->x.getVersionNo()+1).orElse(source.getVersionNo()+1);Workflow clone=Workflow.builder().workflowCode(source.getWorkflowCode()).versionNo(version).workflowName(name==null||name.isBlank()?source.getWorkflowName():name).serviceType(source.getServiceType()).description(source.getDescription()).inputSchemaJson(copy(source.getInputSchemaJson())).inputUiJson(copy(source.getInputUiJson())).defaultInputsJson(copy(source.getDefaultInputsJson())).outputMappingJson(copy(source.getOutputMappingJson())).requiredCapabilitiesJson(copy(source.getRequiredCapabilitiesJson())).maxDurationSec(source.getMaxDurationSec()).maxTotalVisits(source.getMaxTotalVisits()).graphVersion(source.getGraphVersion()).schemaVersion(source.getSchemaVersion()).status(WorkflowStatus.DRAFT).acceptsNewJobs(false).build();clone=workflowRepository.saveAndFlush(clone);
        Map<UUID,Task> taskMap=new HashMap<>();for(Task old:taskRepository.findByWorkflowWorkflowIdOrderBySequenceNoAsc(id)){Task n=taskRepository.save(Task.builder().workflow(clone).taskCode(old.getTaskCode()).taskName(old.getTaskName()).sequenceNo(old.getSequenceNo()).description(old.getDescription()).configJson(copy(old.getConfigJson())).build());taskMap.put(old.getTaskId(),n);}taskRepository.flush();
        Map<UUID,Step> stepMap=new HashMap<>();for(Step old:stepRepository.findByWorkflowWorkflowIdOrderBySequenceNoAsc(id)){Step n=stepRepository.save(Step.builder().workflow(clone).task(taskMap.get(old.getTask().getTaskId())).stepType(old.getStepType()).stepCode(old.getStepCode()).stepName(old.getStepName()).sequenceNo(old.getSequenceNo()).description(old.getDescription()).enabled(old.isEnabled()).paramsJson(copy(old.getParamsJson())).inputMappingJson(copy(old.getInputMappingJson())).timeoutSec(old.getTimeoutSec()).maxAttempts(old.getMaxAttempts()).retryDelaySec(old.getRetryDelaySec()).retryCodesJson(copy(old.getRetryCodesJson())).maxVisits(old.getMaxVisits()).auditLevel(old.getAuditLevel()).build());stepMap.put(old.getStepId(),n);}stepRepository.flush();
        for(StepTransition old:transitionRepository.findByWorkflowWorkflowIdOrderByFromStepSequenceNoAscOutcomeAscPriorityAsc(id))transitionRepository.save(StepTransition.builder().workflow(clone).fromStep(stepMap.get(old.getFromStep().getStepId())).toStep(old.getToStep()==null?null:stepMap.get(old.getToStep().getStepId())).outcome(old.getOutcome()).conditionJson(nullableJson(old.getConditionJson())).priority(old.getPriority()).defaultTransition(old.isDefaultTransition()).terminalStatus(old.getTerminalStatus()).build());clone.setStartStep(stepMap.get(source.getStartStep().getStepId()));audit(actorId,"WORKFLOW_CLONED","Workflow",clone.getWorkflowId(),md);return detail(clone);}
    @Override @Transactional public void deleteDraft(UUID id,UUID actorId,RequestMetadata md){Workflow w=workflow(id);if(w.getStatus()!=WorkflowStatus.DRAFT)throw new BusinessRuleException("Only DRAFT workflow can be deleted");if(jobRepository.existsByWorkflowWorkflowId(id))throw new ConflictException("Workflow is referenced by Job history");w.setStartStep(null);workflowRepository.flush();workflowRepository.delete(w);audit(actorId,"WORKFLOW_DELETED","Workflow",id,md);}

    private WorkflowDetailResponse detail(Workflow w){return new WorkflowDetailResponse(mapper.toResponse(w),taskRepository.findByWorkflowWorkflowIdOrderBySequenceNoAsc(w.getWorkflowId()).stream().map(mapper::toResponse).toList(),stepRepository.findByWorkflowWorkflowIdOrderBySequenceNoAsc(w.getWorkflowId()).stream().map(mapper::toResponse).toList(),transitionRepository.findByWorkflowWorkflowIdOrderByFromStepSequenceNoAscOutcomeAscPriorityAsc(w.getWorkflowId()).stream().map(mapper::toResponse).toList(),w.getCompiledDefinitionJson());}
    private PublishedWorkflowSummaryResponse publishedSummary(Workflow w){return new PublishedWorkflowSummaryResponse(w.getWorkflowId(),w.getWorkflowCode(),w.getVersionNo(),w.getWorkflowName(),w.getServiceType(),w.getDescription(),w.getRequiredCapabilitiesJson(),w.getMaxDurationSec(),w.getGraphVersion(),w.getSchemaVersion(),w.getPublishedAt());}
    private Workflow workflow(UUID id){return workflowRepository.findById(id).orElseThrow(()->new ResourceNotFoundException("Workflow not found: "+id));}
    private Workflow editable(UUID id){Workflow w=workflow(id);if(jobRepository.existsByWorkflowWorkflowId(id))throw new ConflictException("Workflow is referenced by Job history");if(w.getStatus()!=WorkflowStatus.DRAFT&&w.getStatus()!=WorkflowStatus.VALIDATED)throw new BusinessRuleException("Published or archived workflow is immutable; clone a new version");return w;}
    private void dirty(Workflow w){if(w.getStatus()==WorkflowStatus.VALIDATED)w.setStatus(WorkflowStatus.DRAFT);w.setCompiledDefinitionJson(null);w.setDefinitionHash(null);w.setAcceptsNewJobs(false);}
    private Task task(UUID wid,UUID id){Task t=taskRepository.findById(id).orElseThrow(()->new ResourceNotFoundException("Task not found: "+id));if(!t.getWorkflow().getWorkflowId().equals(wid))throw new BusinessRuleException("Task does not belong to workflow");return t;}
    private Step step(UUID wid,UUID id){Step s=stepRepository.findById(id).orElseThrow(()->new ResourceNotFoundException("Step not found: "+id));if(!s.getWorkflow().getWorkflowId().equals(wid))throw new BusinessRuleException("Step does not belong to workflow");return s;}
    private StepTransition transition(UUID wid,UUID id){StepTransition t=transitionRepository.findById(id).orElseThrow(()->new ResourceNotFoundException("Transition not found: "+id));if(!t.getWorkflow().getWorkflowId().equals(wid))throw new BusinessRuleException("Transition does not belong to workflow");return t;}
    private StepType stepType(String code){try{return StepType.valueOf(code);}catch(IllegalArgumentException e){throw new BusinessRuleException("Unknown StepType: "+code);}}
    private Step stepFrom(StepRequest r,Workflow w,Task t,StepType type){Step s=new Step();s.setWorkflow(w);apply(r,s,t,type);return s;}
    private void apply(StepRequest r,Step s,Task t,StepType type){s.setTask(t);s.setStepType(type);s.setStepCode(r.stepCode());s.setStepName(r.stepName());s.setSequenceNo(r.sequenceNo());s.setDescription(r.description());s.setEnabled(r.enabled());s.setParamsJson(r.paramsJson());s.setInputMappingJson(r.inputMappingJson());s.setTimeoutSec(r.timeoutSec());s.setMaxAttempts(r.maxAttempts());s.setRetryDelaySec(r.retryDelaySec());s.setRetryCodesJson(r.retryCodesJson());s.setMaxVisits(r.maxVisits());s.setAuditLevel(r.auditLevel());}
    private StepTransition transitionFrom(UUID wid,TransitionRequest r,Workflow w){StepTransition t=new StepTransition();t.setWorkflow(w);apply(r,t,wid);return t;}
    private void apply(TransitionRequest r,StepTransition t,UUID wid){t.setFromStep(step(wid,r.fromStepId()));t.setToStep(r.toStepId()==null?null:step(wid,r.toStepId()));t.setOutcome(r.outcome());t.setConditionJson(nullableJson(r.conditionJson()));t.setPriority(r.priority());t.setDefaultTransition(r.defaultTransition());t.setTerminalStatus(r.terminalStatus());}
    private void validateTransitionRequest(TransitionRequest r){if((r.toStepId()==null)==(r.terminalStatus()==null))throw new BusinessRuleException("Transition requires exactly one toStepId or terminalStatus");if(r.defaultTransition()&&r.conditionJson()!=null&&!r.conditionJson().isNull())throw new BusinessRuleException("Default transition cannot have condition");if(!r.defaultTransition()&&(r.conditionJson()==null||r.conditionJson().isNull()))throw new BusinessRuleException("Non-default transition requires condition");}
    private Account account(UUID id){return accountRepository.findById(id).orElseThrow(()->new ResourceNotFoundException("Account not found: "+id));}
    private void audit(UUID actorId,String action,String type,UUID id,RequestMetadata md){auditService.record(account(actorId),action,type,id,null,Map.of("action",action),md);}
    private UUID stableId(String value){return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));}
    private JsonNode nullableJson(JsonNode n){return n==null||n.isNull()?null:n.deepCopy();}
    private JsonNode copy(JsonNode n){return n==null?null:n.deepCopy();}
}
