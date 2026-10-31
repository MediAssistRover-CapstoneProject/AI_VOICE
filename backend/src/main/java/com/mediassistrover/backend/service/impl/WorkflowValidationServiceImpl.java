package com.mediassistrover.backend.service.impl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.WorkflowValidationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service @RequiredArgsConstructor
public class WorkflowValidationServiceImpl implements WorkflowValidationService {
    private final TaskRepository taskRepository; private final StepRepository stepRepository;
    private final StepTransitionRepository transitionRepository; private final ObjectMapper objectMapper;
    private final com.mediassistrover.backend.validation.WorkflowJsonValidator jsonValidator;
    private final com.mediassistrover.backend.validation.WorkflowStepContractValidator stepContractValidator;

    @Override public CompilationResult validateAndCompile(Workflow workflow){
        com.mediassistrover.backend.validation.StepContractVersions.requireWorkflow(workflow.getSchemaVersion(),workflow.getGraphVersion());
        jsonValidator.object(workflow.getInputSchemaJson(),"inputSchemaJson");
        jsonValidator.object(workflow.getInputUiJson(),"inputUiJson");
        jsonValidator.object(workflow.getDefaultInputsJson(),"defaultInputsJson");
        jsonValidator.object(workflow.getOutputMappingJson(),"outputMappingJson");
        jsonValidator.array(workflow.getRequiredCapabilitiesJson(),"requiredCapabilitiesJson");
        List<Task> tasks=taskRepository.findByWorkflowWorkflowIdOrderBySequenceNoAsc(workflow.getWorkflowId());
        List<Step> steps=stepRepository.findByWorkflowWorkflowIdOrderBySequenceNoAsc(workflow.getWorkflowId());
        List<StepTransition> transitions=transitionRepository.findByWorkflowWorkflowIdOrderByFromStepSequenceNoAscOutcomeAscPriorityAsc(workflow.getWorkflowId());
        if(tasks.isEmpty()||steps.isEmpty())throw new BusinessRuleException("Workflow must contain at least one Task and Step");
        if(workflow.getStartStep()==null)throw new BusinessRuleException("Workflow start step is required");
        for(Task task:tasks)jsonValidator.object(task.getConfigJson(),"configJson");
        Set<String> codes=new HashSet<>();Set<Integer> orders=new HashSet<>();
        for(Step step:steps){
            if(!codes.add(step.getStepCode().toUpperCase(Locale.ROOT))||!orders.add(step.getSequenceNo()))throw new BusinessRuleException("Duplicate step code/order");
            jsonValidator.object(step.getParamsJson(),"paramsJson");jsonValidator.object(step.getInputMappingJson(),"inputMappingJson");
            jsonValidator.array(step.getRetryCodesJson(),"retryCodesJson");
        }
        stepContractValidator.validate(workflow,steps);
        Map<UUID,Step> byId=new LinkedHashMap<>(); for(Step s:steps){byId.put(s.getStepId(),s);if(!s.isEnabled())throw new BusinessRuleException("Disabled step cannot be published: "+s.getStepCode());if(s.getStepType().getStepTypeCode().equals("CONTROL_HARDWARE")&&s.getMaxAttempts()!=1)throw new BusinessRuleException("CONTROL_HARDWARE must use maxAttempts=1");}
        if(!byId.containsKey(workflow.getStartStep().getStepId()))throw new BusinessRuleException("Start step must belong to workflow");
        Map<UUID,List<StepTransition>> outgoing=new HashMap<>();Set<String> defaults=new HashSet<>();
        for(StepTransition t:transitions){
            if(t.getPriority()<1)throw new BusinessRuleException("Transition priority starts at 1");
            if(!t.isDefaultTransition())jsonValidator.condition(t.getConditionJson());
            if(!byId.containsKey(t.getFromStep().getStepId())||(t.getToStep()!=null&&!byId.containsKey(t.getToStep().getStepId())))throw new BusinessRuleException("Transition steps must belong to workflow");
            if((t.getToStep()==null)==(t.getTerminalStatus()==null))throw new BusinessRuleException("Transition must have exactly one target or terminal status");
            if(t.isDefaultTransition()){if(t.getConditionJson()!=null&&!t.getConditionJson().isNull())throw new BusinessRuleException("Default transition cannot have condition");String key=t.getFromStep().getStepId()+":"+t.getOutcome();if(!defaults.add(key))throw new BusinessRuleException("Only one default transition is allowed per step/outcome");}
            else if(t.getConditionJson()==null||t.getConditionJson().isNull())throw new BusinessRuleException("Non-default transition requires condition");
            outgoing.computeIfAbsent(t.getFromStep().getStepId(),ignored->new ArrayList<>()).add(t);
        }
        for(Step s:steps)for(TransitionOutcome outcome:TransitionOutcome.values())if(!defaults.contains(s.getStepId()+":"+outcome))throw new BusinessRuleException("Missing default "+outcome+" transition for step "+s.getStepCode());
        for(Step s:steps)for(TransitionOutcome outcome:TransitionOutcome.values()){
            List<StepTransition> routes=outgoing.getOrDefault(s.getStepId(),List.of()).stream().filter(t->t.getOutcome()==outcome).toList();
            int max=routes.stream().mapToInt(StepTransition::getPriority).max().orElse(1);
            if(routes.stream().anyMatch(t->t.isDefaultTransition()&&t.getPriority()!=max))throw new BusinessRuleException("Default transition must have the last priority");
        }
        Set<UUID> visited=new HashSet<>();Deque<UUID> queue=new ArrayDeque<>();queue.add(workflow.getStartStep().getStepId());
        while(!queue.isEmpty()){UUID id=queue.remove();if(!visited.add(id))continue;for(StepTransition t:outgoing.getOrDefault(id,List.of()))if(t.getToStep()!=null)queue.add(t.getToStep().getStepId());}
        if(visited.size()!=steps.size())throw new BusinessRuleException("All enabled steps must be reachable from start step");
        int visits=steps.stream().mapToInt(Step::getMaxVisits).sum();if(workflow.getMaxTotalVisits()<steps.size()||visits<steps.size())throw new BusinessRuleException("Workflow visit limits are invalid");
        ObjectNode root=objectMapper.createObjectNode();root.put("workflowCode",workflow.getWorkflowCode());root.put("versionNo",workflow.getVersionNo());root.put("graphVersion",workflow.getGraphVersion());root.put("schemaVersion",workflow.getSchemaVersion());root.put("startStepId",workflow.getStartStep().getStepId().toString());root.set("inputSchema",canonical(workflow.getInputSchemaJson()));root.set("defaultInputs",canonical(workflow.getDefaultInputsJson()));root.set("outputMapping",canonical(workflow.getOutputMappingJson()));root.set("requiredCapabilities",canonical(workflow.getRequiredCapabilitiesJson()));
        ArrayNode taskNodes=root.putArray("tasks");for(Task t:tasks){ObjectNode n=taskNodes.addObject();n.put("taskId",t.getTaskId().toString());n.put("taskCode",t.getTaskCode());n.put("sequenceNo",t.getSequenceNo());n.set("config",canonical(t.getConfigJson()));}
        ArrayNode stepNodes=root.putArray("steps");for(Step s:steps){ObjectNode n=stepNodes.addObject();n.put("stepId",s.getStepId().toString());n.put("taskId",s.getTask().getTaskId().toString());n.put("stepCode",s.getStepCode());n.put("stepType",s.getStepType().getStepTypeCode());n.put("sequenceNo",s.getSequenceNo());n.put("timeoutSec",s.getTimeoutSec());n.put("maxAttempts",s.getMaxAttempts());n.put("retryDelaySec",s.getRetryDelaySec());n.put("maxVisits",s.getMaxVisits());n.put("auditLevel",s.getAuditLevel().name());n.set("params",canonical(s.getParamsJson()));n.set("inputMapping",canonical(s.getInputMappingJson()));n.set("retryCodes",canonical(s.getRetryCodesJson()));}
        ArrayNode transitionNodes=root.putArray("transitions");for(StepTransition t:transitions){ObjectNode n=transitionNodes.addObject();n.put("transitionId",t.getTransitionId().toString());n.put("fromStepId",t.getFromStep().getStepId().toString());if(t.getToStep()!=null)n.put("toStepId",t.getToStep().getStepId().toString());else n.put("terminalStatus",t.getTerminalStatus().name());n.put("outcome",t.getOutcome().name());n.put("priority",t.getPriority());n.put("default",t.isDefaultTransition());if(t.getConditionJson()!=null)n.set("condition",canonical(t.getConditionJson()));}
        try{return new CompilationResult(root,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsString(root).getBytes(StandardCharsets.UTF_8))),tasks.size(),steps.size(),transitions.size(),List.of());}catch(Exception e){throw new IllegalStateException("Cannot compile workflow",e);}
    }
    private JsonNode canonical(JsonNode node){if(node==null||node.isNull())return NullNode.instance;if(node.isObject()){ObjectNode out=objectMapper.createObjectNode();List<String> names=new ArrayList<>();node.fieldNames().forEachRemaining(names::add);Collections.sort(names);names.forEach(n->out.set(n,canonical(node.get(n))));return out;}if(node.isArray()){ArrayNode out=objectMapper.createArrayNode();node.forEach(n->out.add(canonical(n)));return out;}return node.deepCopy();}
}
