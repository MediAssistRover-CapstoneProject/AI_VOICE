package com.mediassistrover.backend.mapper;

import com.mediassistrover.backend.dto.job.response.JobResponses.*;
import com.mediassistrover.backend.dto.request.response.ServiceRequestResponses.ServiceRequestResponse;
import com.mediassistrover.backend.dto.schedule.response.ScheduleResponse;
import com.mediassistrover.backend.entity.*;
import org.mapstruct.*;

@Mapper(componentModel="spring",unmappedTargetPolicy=ReportingPolicy.ERROR)
public interface RequestJobMapper {
    @Mapping(target="requestedByAccountId",source="requestedByAccount.accountId")
    @Mapping(target="requestedRobotId",source="requestedRobot.robotId")
    ServiceRequestResponse toResponse(ServiceRequest value);

    @Mapping(target="workflowId",source="workflow.workflowId") @Mapping(target="robotId",source="robot.robotId")
    @Mapping(target="createdByAccountId",source="createdByAccount.accountId")
    ScheduleResponse toResponse(Schedule value);

    @Mapping(target="workflowId",source="workflow.workflowId") @Mapping(target="serviceRequestId",source="serviceRequest.serviceRequestId")
    @Mapping(target="scheduleId",source="schedule.scheduleId") @Mapping(target="robotId",source="robot.robotId")
    @Mapping(target="mapId",source="map.mapId") @Mapping(target="assignedStaffAccountId",source="assignedStaffAccount.accountId")
    @Mapping(target="selectedJobStepId",source="selectedJobStep.jobStepId")
    JobResponse toResponse(Job value);

    @Mapping(target="jobId",source="job.jobId") @Mapping(target="taskId",source="task.taskId")
    JobTaskResponse toResponse(JobTask value);

    @Mapping(target="jobTaskId",source="jobTask.jobTaskId") @Mapping(target="stepId",source="step.stepId")
    JobStepResponse toResponse(JobStep value);

    @Mapping(target="jobId",source="job.jobId") @Mapping(target="robotId",source="robot.robotId")
    @Mapping(target="jobTaskId",source="jobTask.jobTaskId") @Mapping(target="jobStepId",source="jobStep.jobStepId")
    @Mapping(target="actorAccountId",source="actorAccount.accountId")
    JobEventResponse toResponse(JobEvent value);
}
