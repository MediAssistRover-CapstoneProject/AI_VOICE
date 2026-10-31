package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.mqtt.*;
import com.mediassistrover.backend.validation.MqttContractValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.stream.*;
import static org.assertj.core.api.Assertions.*;

class MqttWireContractTest {
    private final MqttWire wire=new MqttWire();
    private final MqttContractValidator validator=new MqttContractValidator();
    record Case(String name,String schema,boolean valid,JsonNode instance) { }
    static Stream<Case> fixtures() throws Exception {
        JsonNode cases=new ObjectMapper().readTree(Path.of("docs/schemas/fixtures/contract-cases.json").toFile());
        return StreamSupport.stream(cases.spliterator(),false).filter(c->c.path("schema").asText().startsWith("mqtt-"))
                .map(c->new Case(c.path("name").asText(),c.path("schema").asText(),c.path("valid").asBoolean(),c.path("instance")));
    }
    @ParameterizedTest @MethodSource("fixtures") void agreesWithDocumentedWireFixtures(Case c) {
        org.assertj.core.api.ThrowableAssert.ThrowingCallable check=()->{
            switch(c.schema()) {
                case "mqtt-command.schema.json" -> validator.command(c.instance());
                case "mqtt-pi-message.schema.json" -> validator.inbound(c.instance());
                case "mqtt-be-response.schema.json" -> validator.response(c.instance());
                default -> throw new AssertionError("Missing test schema: "+c.schema());
            }
        };
        if(c.valid()) assertThatCode(check).as(c.name()).doesNotThrowAnyException();
        else assertThatThrownBy(check).as(c.name()).isInstanceOf(com.mediassistrover.backend.exception.BusinessRuleException.class);
    }
    @Test void rejectsDuplicateFieldsTrailingObjectsNonObjectsAndOversizedPayloads() {
        for(String input:new String[]{"{\"accepted\":true,\"accepted\":false}","{} {}","[]","null"})
            assertThatThrownBy(()->wire.parse(input.getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->wire.parse(new byte[1_000_001])).hasMessageContaining("exceeds");
    }
    @Test void receiptHashIgnoresOnlyTransportAndObjectOrder() {
        ObjectNode first=wire.object().put("transportSessionId","old").put("messageId","message");first.putObject("payload").put("a",1).put("b",2);
        ObjectNode replay=wire.object();replay.putObject("payload").put("b",2).put("a",1);replay.put("messageId","message").put("transportSessionId","new");
        assertThat(wire.canonicalHash(first)).isEqualTo(wire.canonicalHash(replay));
        ((ObjectNode)replay.path("payload")).put("a",2);assertThat(wire.canonicalHash(first)).isNotEqualTo(wire.canonicalHash(replay));
    }
    @Test void mapPoseRejectsCoordinatesThatCannotBePersistedAndYawOutsideMapFrameRange() throws Exception {
        ObjectMapper json=new ObjectMapper();
        ObjectNode heartbeat=(ObjectNode)json.readTree("""
                {"protocolVersion":"1.0","messageId":"11111111-1111-4111-8111-111111111111","operationKey":"heartbeat-1",
                 "correlationId":"22222222-2222-4222-8222-222222222222","robotId":"11111111-1111-4111-8111-111111111111",
                 "jobId":null,"jobStepId":null,"occurredAt":"2026-10-20T00:00:00Z","producerSessionId":"33333333-3333-4333-8333-333333333333",
                 "sequenceNo":1,"transportSessionId":"44444444-4444-4444-8444-444444444444","messageType":"HEARTBEAT",
                 "payload":{"status":"IDLE","runtimeVersion":"test","protocolMajor":1,"capabilities":[],"activeJobStepId":null,
                 "currentMapId":"55555555-5555-4555-8555-555555555555","mapAssetHash":null,"mappingSession":null,
                 "telemetry":{"map_pose":{"value":{"x_m":1,"y_m":2,"yaw_rad":0,"map_id":"55555555-5555-4555-8555-555555555555"},"age_sec":0.1,"fresh":true}}}}
                """);
        var autonomous=wire.object().put("state","IDLE").put("available",false).putNull("run_id")
                .put("control_selected",false).put("vehicle","articulated").put("finished",false).put("coverage_complete",false)
                .put("reason","NOT_STARTED").put("elapsed_sec",0).put("travel_m",0).putNull("start_pose")
                .put("max_duration_sec",180).put("profile_max_duration_sec",180).put("stop_on_return",false);
        ((ObjectNode)heartbeat.path("payload")).set("mappingSession",wire.object().set("autonomy",autonomous));
        assertThatCode(()->validator.inbound(heartbeat)).doesNotThrowAnyException();
        autonomous.set("manual_control",wire.object().put("source","gamepad").put("layout","vendor_mecanum")
                .put("session_required",false).put("forward_mps",.25).put("angular_rps",.8).put("continuous",true));
        assertThatCode(()->validator.inbound(heartbeat)).doesNotThrowAnyException();
        autonomous.put("coverage_complete",true);
        assertThatThrownBy(()->validator.inbound(heartbeat)).hasMessageContaining("contract");
        autonomous.put("state","STOPPED").put("finished",true).put("reason","COVERAGE_COMPLETE").put("exploration_mode","frontier");
        autonomous.set("exploration",wire.object().put("enabled",true).put("state","COMPLETE").put("frontier_count",0));
        assertThatCode(()->validator.inbound(heartbeat)).doesNotThrowAnyException();
        ((ObjectNode)autonomous.path("exploration")).put("state","BLOCKED").put("frontier_count",1);
        assertThatThrownBy(()->validator.inbound(heartbeat)).hasMessageContaining("contract");
        autonomous.put("coverage_complete",false);
        ((ObjectNode)heartbeat.at("/payload/telemetry/map_pose/value")).put("yaw_rad",4);
        assertThatThrownBy(()->validator.inbound(heartbeat)).hasMessageContaining("contract");
        ((ObjectNode)heartbeat.at("/payload/telemetry/map_pose/value")).put("yaw_rad",0).put("x_m",100000000);
        assertThatThrownBy(()->validator.inbound(heartbeat)).hasMessageContaining("contract");
    }
}
