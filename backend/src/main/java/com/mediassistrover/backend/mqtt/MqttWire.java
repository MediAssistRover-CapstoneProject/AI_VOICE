package com.mediassistrover.backend.mqtt;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;

@Component
public class MqttWire {
    private final ObjectMapper mapper=new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(1_000_000).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Envelope(String protocolVersion,UUID messageId,String operationKey,UUID correlationId,UUID robotId,
            UUID jobId,UUID jobStepId,String messageType,String occurredAt,UUID producerSessionId,long sequenceNo,UUID transportSessionId,JsonNode payload) {
        public Instant time() { return Instant.parse(occurredAt); }
    }
    public ObjectNode parse(byte[] bytes) {
        if (bytes.length>1_000_000) throw new IllegalArgumentException("MQTT payload exceeds 1000000 bytes");
        try { JsonNode node=mapper.readTree(bytes); if (!(node instanceof ObjectNode object)) throw new IllegalArgumentException("MQTT payload must be an object"); return object; }
        catch (java.io.IOException ex) { throw new IllegalArgumentException("Invalid MQTT JSON",ex); }
    }
    public Envelope envelope(ObjectNode node) {
        try { return mapper.treeToValue(node,Envelope.class); }
        catch (java.io.IOException ex) { throw new IllegalArgumentException("Invalid MQTT envelope",ex); }
    }
    public byte[] bytes(JsonNode node) { return json(node).getBytes(StandardCharsets.UTF_8); }
    public String json(JsonNode node) { try { return mapper.writeValueAsString(node); } catch (java.io.IOException ex) { throw new IllegalArgumentException(ex); } }
    public ObjectNode object() { return mapper.createObjectNode(); }
    public JsonNode tree(Object value) {return mapper.valueToTree(value);}
    public String canonicalHash(ObjectNode envelope) {
        ObjectNode canonical=envelope.deepCopy(); canonical.remove("transportSessionId");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(sort(canonical)))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private JsonNode sort(JsonNode node) {
        if (node.isObject()) { ObjectNode sorted=object();var names=new TreeSet<String>();node.fieldNames().forEachRemaining(names::add);names.forEach(name->sorted.set(name,sort(node.get(name))));return sorted; }
        if (node.isArray()) { ArrayNode sorted=mapper.createArrayNode();node.forEach(value->sorted.add(sort(value)));return sorted; }
        return node.deepCopy();
    }
    public ObjectNode helloAck(UUID robot,UUID session,boolean accepted) {
        return object().put("messageType","HELLO_ACK").put("robotId",robot.toString()).put("transportSessionId",session.toString()).put("contract","MAR-STEP-2").put("accepted",accepted);
    }
    public ObjectNode eventAck(UUID robot,UUID session,UUID message,boolean accepted) {
        return object().put("messageType","EVENT_ACK").put("robotId",robot.toString()).put("transportSessionId",session.toString()).put("messageId",message.toString()).put("accepted",accepted);
    }
}
