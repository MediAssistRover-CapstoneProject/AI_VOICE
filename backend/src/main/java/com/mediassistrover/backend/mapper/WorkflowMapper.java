package com.mediassistrover.backend.mapper;

import com.mediassistrover.backend.dto.workflow.request.WorkflowRequests.*;
import com.mediassistrover.backend.dto.workflow.response.WorkflowResponses.*;
import com.mediassistrover.backend.entity.*;
import org.mapstruct.*;

@Mapper(componentModel="spring",unmappedTargetPolicy=ReportingPolicy.IGNORE)
public interface WorkflowMapper {
    @Mapping(target="startStepId",source="startStep.stepId")
    @Mapping(target="publishedByAccountId",source="publishedByAccount.accountId")
    WorkflowResponse toResponse(Workflow entity);
    @Mapping(target="workflowId",source="workflow.workflowId") TaskResponse toResponse(Task entity);
    @Mapping(target="workflowId",source="workflow.workflowId") @Mapping(target="taskId",source="task.taskId")
    @Mapping(target="stepTypeCode",source="stepType.stepTypeCode") StepResponse toResponse(Step entity);
    @Mapping(target="workflowId",source="workflow.workflowId") @Mapping(target="fromStepId",source="fromStep.stepId")
    @Mapping(target="toStepId",source="toStep.stepId") TransitionResponse toResponse(StepTransition entity);
    @Mapping(target="workflowId",ignore=true) @Mapping(target="startStep",ignore=true) @Mapping(target="publishedByAccount",ignore=true)
    @Mapping(target="compiledDefinitionJson",ignore=true) @Mapping(target="definitionHash",ignore=true)
    @Mapping(target="status",ignore=true) @Mapping(target="acceptsNewJobs",ignore=true) @Mapping(target="publishedAt",ignore=true)
    @Mapping(target="rowVersion",ignore=true) @Mapping(target="createdAt",ignore=true) @Mapping(target="updatedAt",ignore=true)
    Workflow toEntity(WorkflowUpsertRequest request);
    @BeanMapping(nullValuePropertyMappingStrategy=NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target="workflowId",ignore=true) @Mapping(target="startStep",ignore=true) @Mapping(target="publishedByAccount",ignore=true)
    @Mapping(target="compiledDefinitionJson",ignore=true) @Mapping(target="definitionHash",ignore=true)
    @Mapping(target="status",ignore=true) @Mapping(target="acceptsNewJobs",ignore=true) @Mapping(target="publishedAt",ignore=true)
    @Mapping(target="rowVersion",ignore=true) @Mapping(target="createdAt",ignore=true) @Mapping(target="updatedAt",ignore=true)
    void update(WorkflowUpsertRequest request,@MappingTarget Workflow entity);
}
