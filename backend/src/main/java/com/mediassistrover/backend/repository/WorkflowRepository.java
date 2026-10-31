package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.Workflow;
import com.mediassistrover.backend.enums.WorkflowStatus;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface WorkflowRepository extends JpaRepository<Workflow,UUID> {
    boolean existsByWorkflowCodeIgnoreCaseAndVersionNo(String code,int version);
    Optional<Workflow> findTopByWorkflowCodeIgnoreCaseOrderByVersionNoDesc(String code);
    Page<Workflow> findByStatus(WorkflowStatus status,Pageable pageable);
    List<Workflow> findByStatusAndAcceptsNewJobsTrueOrderByWorkflowCodeAscVersionNoDesc(WorkflowStatus status);
    @Query("select w from Workflow w where w.status=:status and w.acceptsNewJobs=true " +
            "and (:serviceType is null or lower(w.serviceType)=lower(:serviceType))")
    Page<Workflow> searchPublished(@Param("status") WorkflowStatus status,
                                   @Param("serviceType") String serviceType,
                                   Pageable pageable);
    Optional<Workflow> findFirstByWorkflowCodeIgnoreCaseAndStatusAndAcceptsNewJobsTrueOrderByVersionNoDesc(String code,WorkflowStatus status);
    Optional<Workflow> findFirstByServiceTypeIgnoreCaseAndStatusAndAcceptsNewJobsTrueOrderByVersionNoDesc(String serviceType,WorkflowStatus status);
    Optional<Workflow> findFirstByServiceTypeIgnoreCaseAndStatusAndAcceptsNewJobsTrueAndSchemaVersionOrderByVersionNoDesc(String serviceType,WorkflowStatus status,int schemaVersion);
    @EntityGraph(attributePaths={"startStep","publishedByAccount"}) Optional<Workflow> findDetailedByWorkflowId(UUID id);
}
