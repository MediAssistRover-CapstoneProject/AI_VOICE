package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.DeliveryRequest;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;
import java.time.Instant;
import org.springframework.data.domain.*;
import com.mediassistrover.backend.enums.ServiceRequestStatus;
public interface DeliveryRequestRepository extends JpaRepository<DeliveryRequest,UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select d from DeliveryRequest d where d.serviceRequestId=:id") Optional<DeliveryRequest> lockById(@Param("id") UUID id);
    @Query("select d from DeliveryRequest d where (:status is null or d.serviceRequest.status=:status) " +
            "and (:senderId is null or d.senderAccount.accountId=:senderId) " +
            "and (:receiverId is null or d.receiverAccount.accountId=:receiverId) " +
            "and (:pickupRoomId is null or d.pickupRoom.roomId=:pickupRoomId) " +
            "and (:deliveryRoomId is null or d.deliveryRoom.roomId=:deliveryRoomId) " +
            "and (:deadlineFrom is null or d.deliveryDeadline>=:deadlineFrom) " +
            "and (:deadlineTo is null or d.deliveryDeadline<:deadlineTo) " +
            "and (:from is null or d.serviceRequest.requestedAt>=:from) " +
            "and (:to is null or d.serviceRequest.requestedAt<:to)")
    Page<DeliveryRequest> search(@Param("status") ServiceRequestStatus status,@Param("senderId") UUID senderId,
            @Param("receiverId") UUID receiverId,@Param("pickupRoomId") UUID pickupRoomId,
            @Param("deliveryRoomId") UUID deliveryRoomId,@Param("deadlineFrom") Instant deadlineFrom,
            @Param("deadlineTo") Instant deadlineTo,@Param("from") Instant from,@Param("to") Instant to,
            Pageable pageable);
}
