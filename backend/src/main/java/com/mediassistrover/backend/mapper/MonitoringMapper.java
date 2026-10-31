package com.mediassistrover.backend.mapper;
import com.mediassistrover.backend.dto.monitoring.response.MonitoringResponses.*;
import com.mediassistrover.backend.entity.*;
import org.mapstruct.*;
@Mapper(componentModel="spring",unmappedTargetPolicy=ReportingPolicy.IGNORE)
public interface MonitoringMapper {
    @Mapping(target="robotId",source="robot.robotId") @Mapping(target="mapId",source="map.mapId")
    @Mapping(target="zoneId",source="zone.zoneId") @Mapping(target="eventPayload",source="eventPayloadJson")
    DetectionResponse toResponse(DetectionEvent entity);
    @Mapping(target="robotId",source="robot.robotId") @Mapping(target="zoneId",source="zone.zoneId")
    @Mapping(target="checkpointId",source="checkpoint.endpointId") @Mapping(target="jobStepId",source="jobStep.jobStepId")
    @Mapping(target="metrics",source="metricsJson") CrowdDensityResponse toResponse(CrowdDensityRecord entity);
    @Mapping(target="mapId",source="map.mapId") @Mapping(target="gridData",source="gridDataJson")
    HeatmapResponse toResponse(HeatmapRecord entity);
}
