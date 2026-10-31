package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.ServiceRequest;
import com.mediassistrover.backend.enums.ServiceRequestStatus;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import com.mediassistrover.backend.enums.ServiceRequestType;
import java.util.*;
import java.time.Instant;
import org.springframework.data.repository.query.Param;
public interface ServiceRequestRepository extends JpaRepository<ServiceRequest,UUID> {
    interface TypeCount { ServiceRequestType getRequestType(); long getTotal(); }
    Optional<ServiceRequest> findByIdempotencyKey(String key);
    Optional<ServiceRequest> findByRelatedIncidentId(UUID incidentId);
    Page<ServiceRequest> findByStatus(ServiceRequestStatus status,Pageable pageable);
    Page<ServiceRequest> findByRequestedByAccountAccountId(UUID accountId,Pageable pageable);
    Page<ServiceRequest> findByRequestedByAccountAccountIdAndStatus(UUID accountId,ServiceRequestStatus status,Pageable pageable);
    @Query("select r from ServiceRequest r where (:status is null or r.status=:status) " +
            "and (:requestType is null or r.requestType=:requestType) " +
            "and (:robotId is null or r.requestedRobot.robotId=:robotId or exists " +
            "(select j.jobId from Job j where j.serviceRequest.serviceRequestId=r.serviceRequestId and j.robot.robotId=:robotId)) " +
            "and (:from is null or r.requestedAt>=:from) and (:to is null or r.requestedAt<:to)")
    Page<ServiceRequest> search(@Param("status") ServiceRequestStatus status,
                                @Param("requestType") ServiceRequestType requestType,
                                @Param("robotId") UUID robotId,
                                @Param("from") Instant from,
                                @Param("to") Instant to,
                                Pageable pageable);
    @Query("select r.requestType as requestType, count(r) as total from ServiceRequest r group by r.requestType")
    List<TypeCount> countGroupedByType();
}
