package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.DeliveryItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface DeliveryItemRepository extends JpaRepository<DeliveryItem,UUID> {
    List<DeliveryItem> findByDeliveryRequestServiceRequestIdOrderByItemCodeAsc(UUID requestId);
}
