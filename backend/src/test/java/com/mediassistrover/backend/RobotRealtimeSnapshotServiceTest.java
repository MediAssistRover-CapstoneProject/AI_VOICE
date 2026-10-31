package com.mediassistrover.backend;

import com.mediassistrover.backend.dto.robot.response.RobotResponses.RobotResponse;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.mapper.RobotMapper;
import com.mediassistrover.backend.mqtt.MqttSessionRegistry;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.RobotRealtimeSnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RobotRealtimeSnapshotServiceTest {
    @Test void reportsTransportHeartbeatAndDispatchReadinessAsSeparateFacts() {
        UUID robotId=UUID.randomUUID(),mapId=UUID.randomUUID();
        Instant now=Instant.parse("2026-10-20T02:00:00Z");Clock clock=Clock.fixed(now,ZoneOffset.UTC);
        RobotRepository robots=mock(RobotRepository.class);RobotLocationHistoryRepository locations=mock(RobotLocationHistoryRepository.class);
        RobotMqttSessionRepository sessions=mock(RobotMqttSessionRepository.class);JobRepository jobs=mock(JobRepository.class);
        RobotMapper mapper=mock(RobotMapper.class);MqttSessionRegistry registry=mock(MqttSessionRegistry.class);
        FacilityMap map=FacilityMap.builder().mapId(mapId).build();Robot robot=Robot.builder().robotId(robotId).currentMap(map).build();
        RobotMqttSession session=new RobotMqttSession();session.setRobotId(robotId);session.setLastHeartbeatAt(now.minusSeconds(2));
        RobotLocationHistory location=RobotLocationHistory.builder().robotLocationHistoryId(UUID.randomUUID()).robot(robot).map(map)
                .xM(BigDecimal.ONE).yM(BigDecimal.TWO).yawRad(BigDecimal.ZERO).frameId("map").source("MQTT_HEARTBEAT_MAP_POSE")
                .fresh(true).capturedAt(now.minusSeconds(2)).receivedAt(now.minusSeconds(1)).build();
        when(robots.findById(robotId)).thenReturn(Optional.of(robot));when(sessions.findById(robotId)).thenReturn(Optional.of(session));
        when(locations.findFirstByRobotRobotIdOrderByCapturedAtDesc(robotId)).thenReturn(Optional.of(location));
        when(jobs.findByRobotRobotIdAndStatusIn(eq(robotId),anyCollection())).thenReturn(List.of());
        when(registry.owned(session)).thenReturn(true);when(registry.fresh(session)).thenReturn(false);
        when(mapper.toResponse(robot)).thenReturn(mock(RobotResponse.class));
        var navigation=mock(com.mediassistrover.backend.service.NavigationSessionService.class);
        when(navigation.localized(robotId,session)).thenReturn(true);
        session.setHelloReceivedAt(now.minusSeconds(10));
        var heartbeat=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();heartbeat.putObject("telemetry").putObject("map_pose").put("fresh",true);
        session.setHeartbeatJson(heartbeat);
        RobotRealtimeSnapshotService service=new RobotRealtimeSnapshotService(robots,locations,sessions,jobs,mapper,registry,clock,navigation);
        ReflectionTestUtils.setField(service,"poseFreshnessSeconds",3L);

        var snapshot=service.snapshot(robotId);

        assertThat(snapshot.mqttConnected()).isTrue();
        assertThat(snapshot.heartbeatFresh()).isTrue();
        assertThat(snapshot.dispatchReady()).isFalse();
        assertThat(snapshot.heartbeatAgeSeconds()).isEqualTo(2.0);
        assertThat(snapshot.latestPose().fresh()).isTrue();
        assertThat(snapshot.latestPose().frameId()).isEqualTo("map");
    }
}
