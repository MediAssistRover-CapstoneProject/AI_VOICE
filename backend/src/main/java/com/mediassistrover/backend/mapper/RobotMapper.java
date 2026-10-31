package com.mediassistrover.backend.mapper;

import com.mediassistrover.backend.dto.robot.request.RobotRequests.*;
import com.mediassistrover.backend.dto.robot.response.RobotResponses.*;
import com.mediassistrover.backend.entity.*;
import org.mapstruct.*;

@Mapper(componentModel="spring",unmappedTargetPolicy=ReportingPolicy.IGNORE)
public interface RobotMapper {
    @Mapping(target="currentMapId",source="currentMap.mapId") RobotResponse toResponse(Robot robot);
    @Mapping(target="currentMap",ignore=true) @Mapping(target="status",constant="OFFLINE")
    @Mapping(target="batteryPercent",ignore=true) Robot toEntity(RobotCreateRequest request);
    @Mapping(target="robotId",ignore=true) @Mapping(target="robotCode",ignore=true)
    @Mapping(target="currentMap",ignore=true) @Mapping(target="status",ignore=true)
    @Mapping(target="createdAt",ignore=true) @Mapping(target="updatedAt",ignore=true)
    @Mapping(target="deviceSecretHash",ignore=true) @Mapping(target="deviceTokenVersion",ignore=true)
    @Mapping(target="deviceSecretRotatedAt",ignore=true) @Mapping(target="rowVersion",ignore=true)
    void update(RobotUpdateRequest request,@MappingTarget Robot robot);
    @Mapping(target="robotId",source="robot.robotId") @Mapping(target="jobId",source="job.jobId") RobotStatusHistoryResponse toResponse(RobotStatusHistory history);
    @Mapping(target="robotId",source="robot.robotId") @Mapping(target="mapId",source="map.mapId") @Mapping(target="jobId",source="job.jobId")
    RobotLocationResponse toResponse(RobotLocationHistory history);
}
