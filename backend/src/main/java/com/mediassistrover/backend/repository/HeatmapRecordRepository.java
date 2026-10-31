package com.mediassistrover.backend.repository;
import com.mediassistrover.backend.entity.HeatmapRecord;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.UUID;
public interface HeatmapRecordRepository extends JpaRepository<HeatmapRecord,UUID> {
    boolean existsByMapMapIdAndHeatmapTypeIgnoreCaseAndPeriodStartAndPeriodEndAndCellSizeM(UUID mapId,String type,Instant start,Instant end,java.math.BigDecimal cellSize);
    @Query("select h from HeatmapRecord h where (:mapId is null or h.map.mapId=:mapId) and (:type is null or upper(h.heatmapType)=upper(:type))")
    Page<HeatmapRecord> search(@Param("mapId") UUID mapId,@Param("type") String type,Pageable pageable);
}
