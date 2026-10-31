package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.Job;
import com.mediassistrover.backend.enums.JobStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.*;
public interface JobRepository extends JpaRepository<Job,UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from Job j where j.jobId=:id")
    Optional<Job> lockById(@Param("id") UUID id);
    interface StatusCount { JobStatus getStatus(); long getTotal(); }
    boolean existsByWorkflowWorkflowId(UUID workflowId);
    Page<Job> findByStatus(JobStatus status,Pageable pageable);
    Page<Job> findByBeOrchestratedTrueAndStatus(JobStatus status,Pageable pageable);
    @Query("select j.jobId from Job j where j.beOrchestrated=true and j.status='QUEUED' and j.robot.status='IDLE' order by j.queuedAt")
    List<UUID> findReadyBeJobIds(Pageable page);
    Page<Job> findByServiceRequestRequestedByAccountAccountId(UUID accountId,Pageable pageable);
    List<Job> findByServiceRequestServiceRequestId(UUID requestId);
    boolean existsByServiceRequestServiceRequestIdAndAssignedStaffAccountAccountId(UUID requestId, UUID accountId);
    Page<Job> findByAssignedStaffAccountAccountIdOrServiceRequestRequestedByAccountAccountId(UUID assigned,UUID requester,Pageable pageable);
    @Query("select j from Job j where (:status is null or j.status=:status) " +
            "and (:workflowId is null or j.workflow.workflowId=:workflowId) " +
            "and (:requestId is null or j.serviceRequest.serviceRequestId=:requestId) " +
            "and (:robotId is null or j.robot.robotId=:robotId) " +
            "and (:from is null or j.queuedAt>=:from) and (:to is null or j.queuedAt<:to)")
    Page<Job> search(@Param("status") JobStatus status,@Param("workflowId") UUID workflowId,
                     @Param("requestId") UUID requestId,@Param("robotId") UUID robotId,
                     @Param("from") Instant from,@Param("to") Instant to,Pageable pageable);
    List<Job> findByRobotRobotIdAndStatusIn(UUID robotId,Collection<JobStatus> statuses);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Job> findByStatusAndRobotIsNullOrderByPriorityDescQueuedAtAsc(JobStatus status,Pageable pageable);
    Optional<Job> findFirstByRobotRobotIdAndStatusOrderByQueuedAtAsc(UUID robotId,JobStatus status);
    boolean existsByScheduleScheduleIdAndScheduleFireAtAndScheduleItemKey(UUID scheduleId,Instant fireAt,String itemKey);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from Job j where j.robot.robotId=:robotId and j.status in :statuses order by j.queuedAt")
    List<Job> lockRobotJobs(@Param("robotId") UUID robotId,@Param("statuses") Collection<JobStatus> statuses);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from Job j where j.status in :statuses and j.dispatchExpiresAt is not null and j.dispatchExpiresAt <= :now")
    List<Job> lockOverdue(@Param("statuses") Collection<JobStatus> statuses,@Param("now") Instant now);
    @Query("select j.status as status, count(j) as total from Job j group by j.status")
    List<StatusCount> countGroupedByStatus();
}
