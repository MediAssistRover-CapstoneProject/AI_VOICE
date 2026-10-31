package com.mediassistrover.backend.mqtt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.mediassistrover.backend.validation.MqttContractValidator;
import java.util.UUID;

@Service @RequiredArgsConstructor @Slf4j
public class MqttDurablePublisher {
    private final MqttPublicationPlans plans;
    private final PahoMqttTransport transport;
    private final MqttWire wire;
    private final MqttContractValidator contracts;
    public void command(UUID id) { afterCommit(()->publishCommand(id)); }
    private void publishCommand(UUID id) {
        if (!transport.connected()) return;
        var plan=plans.command(id);if (plan==null) return;contracts.command(plan.payload());
        transport.publish(MqttTopics.topic(plan.robotId(),"commands"),wire.bytes(plan.payload()),1)
                .thenRun(()->plans.commandPublished(id,plan.sessionId())).exceptionally(error->{log.warn("MQTT command {} not confirmed published; durable outbox remains",id);return null;});
    }
    public void acknowledgement(UUID id,boolean force) { afterCommit(()->publishAcknowledgement(id,force)); }
    public void rejection(UUID robot,com.fasterxml.jackson.databind.JsonNode response) {
        contracts.response(response);var snapshot=response.deepCopy();
        afterCommit(()->{if(transport.connected()) transport.publish(MqttTopics.topic(robot,"event-acks"),wire.bytes(snapshot),1);});
    }
    private void publishAcknowledgement(UUID id,boolean force) {
        if (!transport.connected()) return;
        var plan=plans.acknowledgement(id,force);if (plan==null) return;contracts.response(plan.payload());
        transport.publish(MqttTopics.topic(plan.robotId(),"event-acks"),wire.bytes(plan.payload()),1)
                .thenRun(()->plans.acknowledged(id,plan.sessionId())).exceptionally(error->{log.warn("MQTT ACK for {} will be retried",id);return null;});
    }
    private void afterCommit(Runnable action) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCommit() {action.run();}
            });
        } else action.run();
    }
}
