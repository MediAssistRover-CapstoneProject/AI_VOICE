package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.CrowdDensityRecord;
import com.mediassistrover.backend.enums.CrowdDensityLevel;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.UUID;
public interface CrowdDensityRecordRepository extends JpaRepository<CrowdDensityRecord,UUID> {
    @Query("select c from CrowdDensityRecord c where (:zoneId is null or c.zone.zoneId=:zoneId) and (:level is null or c.densityLevel=:level) and (:from is null or c.capturedAt>=:from) and (:to is null or c.capturedAt<:to)")
    Page<CrowdDensityRecord> search(@Param("zoneId") UUID zoneId,@Param("level") CrowdDensityLevel level,@Param("from") Instant from,@Param("to") Instant to,Pageable pageable);
}
