package com.mediassistrover.backend.mqtt;

import com.mediassistrover.backend.validation.*;
import com.mediassistrover.backend.service.NavigationProfileService;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.enums.MapStatus;
import java.time.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MqttBridgeTest {
    private final MqttWire wire=new MqttWire();
    @Test void canonicalTopicsAndSubscriptions() {
        UUID id=UUID.randomUUID();
        assertThat(MqttTopics.parse(MqttTopics.topic(id,"mapping/preview")).lane()).isEqualTo("mapping/preview");
        assertThat(MqttTopics.parse(MqttTopics.topic(id,"profile-acks")).robotId()).isEqualTo(id);
        assertThat(MqttTopics.parse(MqttTopics.topic(id,"map-preview")).lane()).isEqualTo("mapping/preview");
        var subscriptions=PahoMqttTransport.subscriptions();assertThat(subscriptions).hasSize(8);
        for(var s:subscriptions) { assertThat(s.isRetainAsPublished()).isTrue();assertThat(s.getRetainHandling()).isEqualTo(2); }
    }
    @Test void publicationsAreBoundedDeduplicatedAndEvenlyPaced() {
        var q=new MqttPublishQueue();byte[] raw={1};
        var first=q.offer("events",raw,1,false,8);
        assertThat(q.offer("events",raw,1,false,8)).isSameAs(first);
        var beat=q.offer("heartbeat",raw,1,true,8);
        var a=q.poll(1_000_000_000L,8);assertThat(a.result()).isSameAs(beat);
        assertThat(q.poll(1_124_999_999L,8)).isNull();
        assertThat(q.poll(1_125_000_000L,8).result()).isSameAs(first);
        for(int i=0;i<8;i++) q.offer("event"+i,raw,1,false,8);
        assertThat(q.offer("overflow",raw,1,false,8)).isCompletedExceptionally();
        var urgent=q.offer("cancel",raw,1,true,8);
        assertThat(urgent).isNotDone();
        assertThat(q.poll(2_000_000_000L,8).result()).isSameAs(urgent);
        q.failPending(new IllegalStateException("disconnected"));assertThat(q.poll(3_000_000_000L,8)).isNull();
    }
    @Test void currentRetainedLastWillIsForwardedButRetainedProfileAckIsRejected() {
        UUID robot=UUID.randomUUID(),session=UUID.randomUUID();
        var props=new MqttProperties();props.setEnabled(true);props.setRobotIds(Set.of(robot));
        var registry=mock(MqttSessionRegistry.class);
        var nav=mock(NavigationProfileService.class);
        var router=new MqttInboundRouter(props,wire,new MqttContractValidator(),registry,mock(MqttInboxStore.class),mock(MqttInboxProcessor.class),mock(MqttDurablePublisher.class),nav,mock(com.mediassistrover.backend.mapping.MappingControlService.class));
        var presence=wire.object().put("robotId",robot.toString()).put("transportSessionId",session.toString()).put("online",false);
        router.route(MqttTopics.topic(robot,"presence"),wire.bytes(presence),1,true);
        verify(registry).offline(robot,session); // Registry rejects a foreign/old session itself.
        var ack=wire.object().put("messageType","NAV_PROFILE_ACK").put("robotId",robot.toString()).put("transportSessionId",session.toString())
                .put("producerSessionId",UUID.randomUUID().toString()).put("profileHash","a".repeat(64)).put("accepted",true)
                .put("mapId",UUID.randomUUID().toString()).put("mapAssetHash","b".repeat(64)).putNull("errorCode");
        assertThatThrownBy(()->router.route(MqttTopics.topic(robot,"profile-acks"),wire.bytes(ack),1,true)).hasMessageContaining("ACK lane");
        router.route(MqttTopics.topic(robot,"profile-acks"),wire.bytes(ack),1,false);verify(nav).confirmMqtt(robot,ack);
        ack.putNull("mapId");assertThatThrownBy(()->new MqttContractValidator().inbound(ack)).hasMessageContaining("contract");
    }
    @Test void profileSchemaAcceptsOnlyFullCatalogMessages() {
        var msg=wire.object().put("messageType","NAV_PROFILE").put("robotId",UUID.randomUUID().toString())
                .put("transportSessionId",UUID.randomUUID().toString()).put("profileVersion","BE-NAV-PROFILE-1").put("profileHash","a".repeat(64));
        UUID map=UUID.randomUUID();var profile=msg.putObject("profile").put("map_id",map.toString()).put("map_asset_sha256","b".repeat(64)).put("frame_id","map").put("map_version",1);
        profile.putObject("endpoints").putObject(UUID.randomUUID().toString()).put("map_id",map.toString()).put("x_m",1).put("y_m",2).put("yaw_rad",0);
        assertThatCode(()->new MqttContractValidator().response(msg)).doesNotThrowAnyException();
        profile.remove("frame_id");assertThatThrownBy(()->new MqttContractValidator().response(msg)).hasMessageContaining("contract");
    }

    @Test void navigationAckRequiresActualMapHashCatalogAndCurrentProducer() {
        UUID robotId=UUID.randomUUID(),mapId=UUID.randomUUID(),transportId=UUID.randomUUID(),producerId=UUID.randomUUID();
        var robots=mock(RobotRepository.class);var profiles=mock(RobotNavigationProfileRepository.class);
        var sessions=mock(RobotMqttSessionRepository.class);var endpoints=mock(EndpointRepository.class);
        var jobs=mock(JobRepository.class);var commands=mock(RobotCommandOutboxRepository.class);
        var registry=mock(MqttSessionRegistry.class);var props=new MqttProperties();props.setEnabled(true);props.setRobotIds(Set.of(robotId));
        var now=Instant.parse("2026-10-13T00:00:00Z");var map=FacilityMap.builder().mapId(mapId).status(MapStatus.ACTIVE)
                .assetHash("b".repeat(64)).frameId("map").versionNo(1).build();
        var robot=Robot.builder().robotId(robotId).currentMap(map).mapAssetHash(map.getAssetHash()).build();
        var session=new RobotMqttSession();session.setRobotId(robotId);session.setTransportSessionId(transportId);
        session.setProducerSessionId(producerId);session.setHandshakeAcked(true);session.setLastHeartbeatAt(now);
        session.setHeartbeatJson(wire.object().put("status","IDLE").putNull("activeJobStepId").putNull("mappingSession"));
        when(robots.findByIdForUpdate(robotId)).thenReturn(Optional.of(robot));
        when(registry.requireCurrent(robotId,transportId)).thenReturn(session);
        var service=new NavigationProfileService(robots,profiles,sessions,endpoints,mock(ZoneRepository.class),jobs,commands,
                registry,props,wire,mock(com.mediassistrover.backend.service.OrchestrationWorkService.class),Clock.fixed(now,ZoneOffset.UTC),
                mock(com.mediassistrover.backend.service.NavigationSessionService.class));
        var manifest=service.snapshot(robot);
        var ack=wire.object().put("messageType","NAV_PROFILE_ACK").put("robotId",robotId.toString())
                .put("transportSessionId",transportId.toString()).put("producerSessionId",producerId.toString())
                .put("profileHash",manifest.profileHash()).put("mapId",mapId.toString()).put("mapAssetHash",map.getAssetHash()).put("accepted",true).putNull("errorCode");
        ack.put("mapAssetHash","c".repeat(64));assertThatThrownBy(()->service.confirmMqtt(robotId,ack)).hasMessageContaining("differs");
        ack.put("mapAssetHash",map.getAssetHash()).put("profileHash","a".repeat(64));
        assertThatThrownBy(()->service.confirmMqtt(robotId,ack)).hasMessageContaining("differs");
        ack.put("profileHash",manifest.profileHash()).put("producerSessionId",UUID.randomUUID().toString());
        assertThatThrownBy(()->service.confirmMqtt(robotId,ack)).hasMessageContaining("producer");
        verify(profiles,never()).saveAndFlush(any());
        ack.put("producerSessionId",producerId.toString());
        assertThat(service.confirmMqtt(robotId,ack).profileHash()).isEqualTo(manifest.profileHash());
        verify(profiles).saveAndFlush(argThat(row->row.getMapId().equals(mapId) && row.getTransportSessionId().equals(transportId)));
    }
}
