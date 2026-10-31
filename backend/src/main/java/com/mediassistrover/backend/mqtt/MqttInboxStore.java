package com.mediassistrover.backend.mqtt;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.RobotMqttInbox;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.UUID;

@Service @RequiredArgsConstructor
public class MqttInboxStore {
    private final RobotMqttInboxRepository inbox;
    private final RobotRepository robots;
    private final RobotMqttSessionRepository sessions;
    private final MqttProperties properties;
    private final MqttSessionRegistry registry;
    private final MqttWire wire;
    private final Clock clock;
    public record Receipt(UUID messageId,boolean duplicate,boolean conflict) { }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Receipt receive(ObjectNode raw,MqttWire.Envelope e) {
        if (!properties.manages(e.robotId()) || !robots.existsById(e.robotId())) throw new BusinessRuleException("Robot is not enrolled for MQTT");
        // INSERT checks the Robot FK. Acquire it before Session to match processing/heartbeat lock order.
        robots.findByIdForUpdate(e.robotId()).orElseThrow();
        com.mediassistrover.backend.entity.RobotMqttSession session=null;
        if (!"HELLO".equals(e.messageType())) registry.requireCurrent(e.robotId(),e.transportSessionId());
        else session=sessions.lock(e.robotId()).orElse(null);
        if (e.time().isAfter(Instant.now(clock).plusSeconds(5))) throw new BusinessRuleException("Future MQTT event");
        String hash=wire.canonicalHash(raw);
        inbox.insert(e.messageId(),e.robotId(),e.producerSessionId(),e.sequenceNo(),e.transportSessionId(),e.messageType(),hash,wire.json(raw),Instant.now(clock));
        RobotMqttInbox row=inbox.lock(e.messageId()).orElseGet(()->inbox.findByProducerSessionIdAndSequenceNo(e.producerSessionId(),e.sequenceNo()).orElseThrow());
        if (!row.getMessageId().equals(e.messageId()) || !row.getCanonicalHash().equals(hash)) return new Receipt(e.messageId(),false,true);
        boolean duplicate=!"PENDING".equals(row.getState());
        // A committed HELLO receipt alone cannot revive a session after BE/broker reconnect.
        // Re-run journal reconciliation when its replay carries a new transport/connection.
        if ("HELLO".equals(e.messageType()) && Boolean.TRUE.equals(row.getAccepted())
                && (!registry.owned(session) || !session.getTransportSessionId().equals(e.transportSessionId()))) {
            row.setState("PENDING");row.setAccepted(null);row.setResponseJson(null);row.setProcessedAt(null);duplicate=false;
        }
        row.setTransportSessionId(e.transportSessionId());row.setRawEnvelope(raw.deepCopy());row.setAckPublishedAt(null);
        if (row.getResponseJson() instanceof ObjectNode response) response.put("transportSessionId",e.transportSessionId().toString());
        return new Receipt(e.messageId(),duplicate,false);
    }
}
