package com.mediassistrover.backend.ai;
import com.mediassistrover.backend.dto.pi.request.PiEnvelope;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.repository.MonitoringIngestionReceiptRepository;
import com.mediassistrover.backend.mqtt.MqttWire;
import com.mediassistrover.backend.exception.ConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.*;
@Service @RequiredArgsConstructor
public class MonitoringReceiptService {
    private final MonitoringIngestionReceiptRepository receipts;
    private final MqttWire wire;
    private final Clock clock;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    public boolean duplicate(PiEnvelope<?> envelope,Robot robot) {
        var receipt=receipts.findById(envelope.messageId()).orElse(null);if(receipt==null)return false;
        if(!receipt.getRobotId().equals(robot.getRobotId()) || !receipt.getMessageType().equals(envelope.messageType()) || !receipt.getPayloadHash().equals(hash(envelope)))
            throw new ConflictException("Monitoring message identity reused with different robot/type/content");return true;
    }
    public void record(PiEnvelope<?> envelope,Robot robot){record(envelope,robot,null);}
    public void record(PiEnvelope<?> envelope,Robot robot,com.fasterxml.jackson.databind.JsonNode provenance){receipts.saveAndFlush(MonitoringIngestionReceipt.builder().messageId(envelope.messageId()).robotId(robot.getRobotId()).messageType(envelope.messageType()).payloadHash(hash(envelope)).receivedAt(Instant.now(clock)).provenanceJson(provenance).build());}
    private String hash(PiEnvelope<?> envelope){return wire.canonicalHash(json.valueToTree(envelope));}
}
