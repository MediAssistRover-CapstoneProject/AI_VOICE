package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.mqtt.*;
import com.mediassistrover.backend.validation.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SpeechContractTest {
    final ObjectMapper json=new ObjectMapper();
    ObjectNode params() throws Exception {
        return (ObjectNode)json.readTree("""
            {"prompt_key":"DEMO_SPEAK","action":"SPEAK","data":{"text":"Xin chào. Robot đã nhận lệnh.","language_code":"vi"}}
            """);
    }
    JobStep visit(ObjectNode inputs) {
        Workflow wf=Workflow.builder().schemaVersion(3).build();
        Step step=Step.builder().stepType(StepType.INTERACT).stepCode("SPEAK").workflow(wf).timeoutSec(90).maxAttempts(1).build();
        Job job=Job.builder().beOrchestrated(true).schemaVersion(3).build();
        return JobStep.builder().step(step).stepTypeSnapshot("INTERACT").jobTask(JobTask.builder().job(job).build()).resolvedInputsJson(inputs).build();
    }
    ObjectNode command(ObjectNode params) {
        String id="11111111-1111-4111-8111-111111111111";
        ObjectNode c=json.createObjectNode().put("protocolVersion","1.0").put("contract","MAR-STEP-2").put("messageType","COMMAND")
            .put("commandId",id).put("correlationId",id).put("robotId",id).put("transportSessionId",id)
            .put("commandType","EXECUTE_STEP").put("expiresAt","2099-01-01T00:00:00Z");
        c.putObject("step").put("jobId",id).put("jobStepId",id).put("stepId",id).put("stepCode","SPEAK")
            .put("stepType","INTERACT").put("visitNo",1).put("operationKey",id+":SPEAK:1").put("timeoutSec",90).set("params",params);
        return c;
    }
    @Test void speechUsesPiWhileDisplayStaysServerAndLegacyIsUnchanged() throws Exception {
        var p=params();var step=visit(p);
        assertThat(new StepExecutionPolicy(new MqttProperties()).isServer(step,p)).isFalse();
        p.put("action","DISPLAY");assertThat(new StepExecutionPolicy(new MqttProperties()).isServer(step,p)).isTrue();
        step.getJobTask().getJob().setBeOrchestrated(false);step.getJobTask().getJob().setSchemaVersion(2);
        p.put("action","SPEAK");assertThat(new StepExecutionPolicy(new MqttProperties()).isServer(step,p)).isTrue();
    }
    @Test void exportsWireCommandAcceptedByBothValidators() throws Exception {
        ObjectNode p=params();var step=visit(p);
        var inputs=new StepInputContractValidator(new WorkflowJsonValidator());inputs.validatePiParams(step.getStep(),p);
        var c=command(p);new MqttContractValidator().command(c);
        Files.createDirectories(Path.of("target/speech-contract"));
        Files.writeString(Path.of("target/speech-contract/be-speech.json"),json.writeValueAsString(c));
    }
    @Test void invalidSpeechRejectedBeforePublication() throws Exception {
        for(String invalid:new String[]{"{\"text\":\"\"}","{\"text\":\"hi\",\"volume\":101}",
                "{\"text\":\"hi\",\"language_code\":\"vi;reboot\"}","{\"text\":\"hi\",\"script\":\"reboot\"}"}) {
            ObjectNode p=params();p.set("data",json.readTree(invalid));
            assertThatThrownBy(()->new MqttContractValidator().command(command(p))).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(()->new StepInputContractValidator(new WorkflowJsonValidator()).validatePiParams(visit(p).getStep(),p)).isInstanceOf(RuntimeException.class);
        }
    }
}
