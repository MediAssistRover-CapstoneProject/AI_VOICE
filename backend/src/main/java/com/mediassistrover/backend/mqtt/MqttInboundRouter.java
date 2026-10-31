package com.mediassistrover.backend.mqtt;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.validation.MqttContractValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;

@Service @RequiredArgsConstructor @Slf4j
public class MqttInboundRouter {
    private final MqttProperties properties;
    private final MqttWire wire;
    private final MqttContractValidator contracts;
    private final MqttSessionRegistry sessions;
    private final MqttInboxStore store;
    private final MqttInboxProcessor processor;
    private final MqttDurablePublisher publisher;
    private final com.mediassistrover.backend.service.NavigationProfileService navigationProfiles;
    private final com.mediassistrover.backend.mapping.MappingControlService mappingControl;
    public void receive(String topic,byte[] bytes,int qos,boolean retained) {
        try {route(topic,bytes,qos,retained);}
        catch (BusinessRuleException | IllegalArgumentException ex) {log.warn("MQTT inbound rejected on {}: {}",topic,ex.getMessage());}
        catch (RuntimeException ex) {log.error("MQTT inbound failed on {}; no application ACK, durable work will retry",topic,ex);}
    }
    public void route(String topic,byte[] bytes,int qos,boolean retained) {
        MqttTopics.Topic lane=MqttTopics.parse(topic);
        if (!properties.manages(lane.robotId())) throw new BusinessRuleException("MQTT robot is not enrolled");
        ObjectNode raw=wire.parse(bytes);
        boolean live=Set.of("mapping/control-acks","camera/preview").contains(lane.lane());
        if(live)contracts.live(raw);else contracts.inbound(raw);
        if (!lane.robotId().equals(UUID.fromString(raw.path("robotId").asText()))) throw new BusinessRuleException("MQTT robotId differs from topic identity");
        String type=raw.path("messageType").asText();
        if(live) {
            if(retained || qos!=0)throw new BusinessRuleException("Live mapping lanes require QoS 0 and retain=false");
            if("mapping/control-acks".equals(lane.lane()) && "MAPPING_CONTROL_ACK".equals(type))mappingControl.acknowledge(lane.robotId(),raw);
            else if("camera/preview".equals(lane.lane()) && "CAMERA_PREVIEW".equals(type))sessions.camera(lane.robotId(),UUID.fromString(raw.path("transportSessionId").asText()),raw.path("payload"));
            else throw new BusinessRuleException("Wrong live mapping messageType");
            return;
        }
        if ("presence".equals(lane.lane())) {
            if(qos!=1)throw new BusinessRuleException("Presence requires QoS 1");
            if (raw.has("messageType")) throw new BusinessRuleException("Presence has no event envelope");
            if (!raw.path("online").asBoolean()) sessions.offline(lane.robotId(),UUID.fromString(raw.path("transportSessionId").asText()));return;
        }
        if ("profile-acks".equals(lane.lane())) {
            if (retained || qos!=1 || !"NAV_PROFILE_ACK".equals(type)) throw new BusinessRuleException("Invalid navigation profile ACK lane");
            navigationProfiles.confirmMqtt(lane.robotId(),raw);return;
        }
        if (retained || qos!=("events".equals(lane.lane())?1:0)) throw new BusinessRuleException("MQTT topic QoS/retain contract mismatch");
        if ("mapping/preview".equals(lane.lane())) {
            if (!"MAP_PREVIEW".equals(type)) throw new BusinessRuleException("Invalid preview messageType");
            sessions.preview(lane.robotId(),UUID.fromString(raw.path("transportSessionId").asText()),raw.path("payload"));return;
        }
        MqttWire.Envelope e=wire.envelope(raw);
        if ("heartbeat".equals(lane.lane())) {if (!"HEARTBEAT".equals(type)) throw new BusinessRuleException("Heartbeat topic requires HEARTBEAT");sessions.heartbeat(e);return;}
        if (Set.of("HEARTBEAT","MAP_PREVIEW").contains(type)) throw new BusinessRuleException("Transient message on durable events topic");
        if ("PROTOCOL_ERROR".equals(type)) {log.warn("Pi protocol error for robot {}: {}",e.robotId(),e.payload().path("errorCode").asText());return;}
        var receipt=store.receive(raw,e);
        if (receipt.conflict()) {
            processor.protocolConflict(e.robotId());
            var ack="HELLO".equals(type)?wire.helloAck(e.robotId(),e.transportSessionId(),false):wire.eventAck(e.robotId(),e.transportSessionId(),e.messageId(),false);
            publisher.rejection(e.robotId(),ack);throw new BusinessRuleException("MQTT identity reused with conflicting business content");
        }
        try {processor.process(receipt.messageId());}
        catch (BusinessRuleException ex) {processor.reject(receipt.messageId(),ex.getMessage());}
        publisher.acknowledgement(receipt.messageId(),true); // Receive/process transactions have both committed.
    }
}
