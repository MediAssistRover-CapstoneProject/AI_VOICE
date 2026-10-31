package com.mediassistrover.backend.mqtt;

import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.exception.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;

@Component @RequiredArgsConstructor @Slf4j
@ConditionalOnProperty(name="app.operations.scheduler-enabled",havingValue="true",matchIfMissing=true)
public class MqttMaintenanceScheduler {
    private final MqttProperties properties;
    private final PahoMqttTransport transport;
    private final RobotMqttInboxRepository inbox;
    private final RobotCommandOutboxRepository commands;
    private final RobotMqttSessionRepository sessionRepository;
    private final MqttSessionRegistry sessions;
    private final MqttInboxProcessor processor;
    private final MqttDurablePublisher publisher;
    private final MqttWire wire;
    private final Clock clock;
    @Scheduled(fixedDelay=250)
    public void durableWork() {
        if (!properties.isEnabled() || !transport.connected()) return;
        for (var id:inbox.pending(PageRequest.of(0,32))) {
            try {processor.process(id);} catch (BusinessRuleException ex) {processor.reject(id,ex.getMessage());} catch (RuntimeException ex) {log.warn("MQTT inbox {} will retry",id);}
        }
        for (var id:inbox.acknowledgements(PageRequest.of(0,64))) try {publisher.acknowledgement(id,false);} catch (RuntimeException ex) {log.warn("MQTT ACK {} will retry",id);}
        for (var id:commands.pending(PageRequest.of(0,32))) try {publisher.command(id);} catch (RuntimeException ex) {log.warn("MQTT outbox {} will retry",id);}
    }
    @Scheduled(fixedDelay=3000)
    public void heartbeats() {
        if (!properties.isEnabled()) return;
        transport.retryInitialConnection();
        for (var session:sessionRepository.findAll()) {
            Instant lastSeen=session.getLastHeartbeatAt()==null?session.getHelloReceivedAt():session.getLastHeartbeatAt();
            if (!session.isClosed() && !lastSeen.isAfter(Instant.now(clock).minusSeconds(15))) {sessions.expire(session.getRobotId(),session.getTransportSessionId(),Instant.now(clock).minusSeconds(15));continue;}
            if (sessions.owned(session) && session.isHandshakeAcked()) transport.publish(MqttTopics.topic(session.getRobotId(),"event-acks"),wire.bytes(wire.object().put("messageType","SERVER_HEARTBEAT")
                    .put("robotId",session.getRobotId().toString()).put("transportSessionId",session.getTransportSessionId().toString())),1);
        }
    }
}
