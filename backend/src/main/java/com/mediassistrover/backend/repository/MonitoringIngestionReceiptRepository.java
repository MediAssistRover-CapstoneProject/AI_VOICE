package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.MonitoringIngestionReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface MonitoringIngestionReceiptRepository extends JpaRepository<MonitoringIngestionReceipt,UUID> { }
