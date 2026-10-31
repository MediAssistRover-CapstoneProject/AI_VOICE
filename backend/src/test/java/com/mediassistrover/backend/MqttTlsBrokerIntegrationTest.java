package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.mqtt.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.*;
import org.eclipse.paho.mqttv5.client.*;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MQTT_TEST_BROKER_SCRIPT",matches=".+")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class MqttTlsBrokerIntegrationTest extends MySqlIntegrationTest {
    @Autowired ObjectMapper json; @Autowired MqttWire wire; @Autowired MqttProperties properties;
    @Autowired PahoMqttTransport transport; @Autowired MqttSessionRegistry sessions;
    @Autowired MqttDurablePublisher publisher; @Autowired MqttInboxProcessor processor;
    @Autowired RobotMqttInboxRepository inbox; @Autowired RobotCommandOutboxRepository commands; @Autowired RobotMqttSessionRepository sessionRows;
    @Autowired JobRepository jobs; @Autowired WorkflowOrchestratorService orchestrator; @Autowired OrchestrationWorkService wakeups;
    @Autowired PlatformTransactionManager manager; @Autowired jakarta.persistence.EntityManager em;

    @Test void realMqtt5TlsHandshakeKeepsSessionAliveAndCompletesOneActionWithBrokerAcl() throws Exception {
        scenario(false);
    }
    @Test void realMqtt5TlsCancellationHandlesErrorBeforeCancelAck() throws Exception {
        scenario(true);
    }
    private void scenario(boolean cancelling) throws Exception {
        var tx=new TransactionTemplate(manager);MqttTestFixture f=tx.execute(status->MqttTestFixture.create(em,json,StepType.WAIT,null));
        if(cancelling)tx.executeWithoutResult(status->em.find(com.mediassistrover.backend.entity.JobStep.class,f.piStepId).getStep()
                .setParamsJson(json.createObjectNode().put("wait_type","DURATION").put("duration_sec",30)));
        int port;try(var socket=new java.net.ServerSocket(0)) {port=socket.getLocalPort();}
        String script=System.getenv("MQTT_TEST_BROKER_SCRIPT"),home=System.getenv("MQTT_TEST_BROKER_HOME");
        Path log=Path.of("target/mqtt-broker/tls-broker-"+port+".log");Files.createDirectories(log.getParent());
        Process broker=new ProcessBuilder("wsl","-d","Ubuntu-22.04","--","env","MQTT_TEST_BROKER_HOME="+home,"sh",script,"start",Integer.toString(port),f.robotId.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        MqttAsyncClient pi=null;var tick=Executors.newScheduledThreadPool(2);
        var error=new AtomicReference<Throwable>();var hello=new CountDownLatch(1);var complete=new CountDownLatch(1);
        var ackId=new AtomicReference<String>();var startedId=new AtomicReference<String>();var resultId=new AtomicReference<String>();var active=new AtomicBoolean();
        var cancelAckId=new AtomicReference<String>();var stepStoppedAcked=new AtomicBoolean();var cancelAcked=new AtomicBoolean();
        var commandCount=new AtomicInteger();var serverHeartbeats=new AtomicInteger();var lastServerHeartbeat=new AtomicLong();
        try {
            await(()->{try(var socket=new java.net.Socket("localhost",port)) {return true;}catch(Exception ex) {return false;}},10_000);
            properties.setEnabled(true);properties.setDispatchEnabled(true);properties.setAllowSimulation(true);properties.setEnabledStepTypes(new HashSet<>(List.of("WAIT")));
            properties.getRobotIds().add(f.robotId);properties.setBrokerUri("ssl://localhost:"+port);properties.setClientId("be-test-"+UUID.randomUUID());
            properties.setUsername("be-test");properties.setPassword("test-password");properties.setTrustStore(Path.of(System.getenv("MQTT_TEST_TRUSTSTORE")));properties.setTrustStorePassword("test-trust");
            transport.start();await(transport::connected,10_000);
            pi=new MqttAsyncClient(properties.getBrokerUri(),"pi-test-"+UUID.randomUUID(),new MemoryPersistence());MqttAsyncClient peer=pi;
            java.util.function.Consumer<ObjectNode> emit=raw->{try {peer.publish(MqttTopics.topic(f.robotId,"events"),wire.bytes(raw),1,false);}catch(Exception ex) {error.compareAndSet(null,ex);}};
            pi.setCallback(new MqttCallback() {
                public void disconnected(MqttDisconnectResponse r) { }
                public void mqttErrorOccurred(MqttException ex) {error.compareAndSet(null,ex);}
                public void deliveryComplete(IMqttToken t) { }
                public void connectComplete(boolean reconnect,String uri) { }
                public void authPacketArrived(int reason,org.eclipse.paho.mqttv5.common.packet.MqttProperties p) { }
                public void messageArrived(String topic,MqttMessage message) {
                    try {
                        ObjectNode raw=wire.parse(message.getPayload());String type=raw.path("messageType").asText();
                        if (type.equals("HELLO_ACK")) {if(raw.path("accepted").asBoolean())hello.countDown();else error.compareAndSet(null,new AssertionError("HELLO rejected"));}
                        else if (type.equals("SERVER_HEARTBEAT")) {serverHeartbeats.incrementAndGet();lastServerHeartbeat.set(System.nanoTime());}
                        else if (type.equals("COMMAND")) {
                            if("CANCEL_STEP".equals(raw.path("commandType").asText())) {
                                assertThat(cancelling).isTrue();commandCount.incrementAndGet();assertThat(raw.has("step")).isFalse();assertThat(raw.path("jobStepId").asText()).isEqualTo(f.piStepId.toString());
                                synchronized(f) {
                                    active.set(false);ObjectNode stopped=f.event(json,"STEP_ERROR",json.createObjectNode().put("status","CANCELLED").put("errorCode","USER_CANCELLED").put("retryable",false).put("physicalStopConfirmed",true));
                                    ((ObjectNode)stopped.path("payload")).putNull("details");resultId.set(stopped.path("messageId").asText());emit.accept(stopped);
                                    ObjectNode stoppedAck=f.event(json,"COMMAND_ACK",json.createObjectNode().put("commandId",raw.path("commandId").asText()).put("commandType","CANCEL_STEP").put("accepted",true).put("status","CANCELLED").put("physicalStopConfirmed",true));
                                    stoppedAck.put("correlationId",raw.path("correlationId").asText());cancelAckId.set(stoppedAck.path("messageId").asText());emit.accept(stoppedAck);
                                }
                                return;
                            }
                            commandCount.incrementAndGet();assertThat(raw.has("jobSteps")).isFalse();assertThat(raw.path("step").path("stepType").asText()).isEqualTo("WAIT");
                            synchronized(f) {
                                f.commandId=UUID.fromString(raw.path("commandId").asText());active.set(true);
                                ObjectNode ack=f.event(json,"COMMAND_ACK",json.createObjectNode().put("commandId",f.commandId.toString()).put("commandType","EXECUTE_STEP").put("accepted",true).put("status","ACCEPTED"));
                                ackId.set(ack.path("messageId").asText());emit.accept(ack);
                            }
                        } else if (type.equals("EVENT_ACK")) {
                            assertThat(raw.path("accepted").asBoolean()).isTrue();String id=raw.path("messageId").asText();
                            synchronized(f) {
                                if (id.equals(ackId.get())) {
                                    ObjectNode params=json.createObjectNode().put("wait_type","DURATION").put("duration_sec",cancelling?30:2);
                                    ObjectNode started=f.event(json,"STEP_STARTED",json.createObjectNode().put("attemptNo",1).put("stepType","WAIT").set("resolvedInputs",params));
                                    startedId.set(started.path("messageId").asText());ackId.set(null);emit.accept(started);
                                } else if (id.equals(startedId.get())) {
                                    startedId.set(null);
                                    if(cancelling) {
                                        tick.execute(()->{try{tx.executeWithoutResult(status->{var job=jobs.findById(f.jobId).orElseThrow();job.setCancelRequestedAt(Instant.now());wakeups.request(job);});orchestrator.advance(f.jobId);}catch(Throwable ex){error.compareAndSet(null,ex);}});
                                        return;
                                    }
                                    tick.schedule(()->{synchronized(f) {ObjectNode p=json.createObjectNode().put("status","SUCCEEDED").put("retryable",false).put("physicalStopConfirmed",true);p.putNull("errorCode");p.putNull("details");p.set("result",f.output(json));
                                        ObjectNode result=f.event(json,"STEP_RESULT",p);resultId.set(result.path("messageId").asText());emit.accept(result);}},2,TimeUnit.SECONDS);
                                } else if (id.equals(resultId.get())) {active.set(false);stepStoppedAcked.set(true);if(!cancelling || cancelAcked.get())complete.countDown();}
                                else if(id.equals(cancelAckId.get())) {cancelAcked.set(true);if(stepStoppedAcked.get())complete.countDown();}
                            }
                        }
                    } catch(Throwable ex) {error.compareAndSet(null,ex);}
                }
            });
            MqttConnectionOptions options=new MqttConnectionOptions();options.setCleanStart(true);options.setSessionExpiryInterval(0L);options.setKeepAliveInterval(10);
            options.setUserName(f.robotId.toString());options.setPassword("test-password".getBytes(StandardCharsets.UTF_8));options.setSocketFactory(PahoMqttTransport.tls(properties));options.setHttpsHostnameVerificationEnabled(true);
            pi.connect(options).waitForCompletion(10_000);
            pi.subscribe(new String[]{MqttTopics.topic(f.robotId,"commands"),MqttTopics.topic(f.robotId,"event-acks")},new int[]{1,1}).waitForCompletion(5000);
            var maintenance=new MqttMaintenanceScheduler(properties,transport,inbox,commands,sessionRows,sessions,processor,publisher,wire,Clock.systemUTC());
            tick.scheduleWithFixedDelay(()->{try {maintenance.durableWork();orchestrator.advance(f.jobId);}catch(Throwable ex) {error.compareAndSet(null,ex);}},0,250,TimeUnit.MILLISECONDS);
            tick.scheduleWithFixedDelay(()->{try {maintenance.heartbeats();}catch(Throwable ex) {error.compareAndSet(null,ex);}},0,3,TimeUnit.SECONDS);
            synchronized(f) {emit.accept(f.hello(json));}
            assertThat(hello.await(10,TimeUnit.SECONDS)).isTrue();long sessionStart=System.nanoTime();
            tx.executeWithoutResult(status->wakeups.request(jobs.findById(f.jobId).orElseThrow()));
            tick.scheduleWithFixedDelay(()->{try {synchronized(f) {ObjectNode hb=f.heartbeat(json);if(active.get()) ((ObjectNode)hb.path("payload")).put("status","BUSY").put("activeJobStepId",f.piStepId.toString());peer.publish(MqttTopics.topic(f.robotId,"heartbeat"),wire.bytes(hb),0,false);}}catch(Throwable ex) {error.compareAndSet(null,ex);}},0,3,TimeUnit.SECONDS);
            assertThat(complete.await(15,TimeUnit.SECONDS)).as("Pi simulator error: %s",error.get()).isTrue();await(()->jobs.findById(f.jobId).orElseThrow().getStatus()==(cancelling?JobStatus.CANCELLED:JobStatus.SUCCEEDED),10_000);
            if(!cancelling){await(()->System.nanoTime()-sessionStart>=18_000_000_000L,20_000);assertThat(serverHeartbeats.get()).isGreaterThanOrEqualTo(5);assertThat(System.nanoTime()-lastServerHeartbeat.get()).isLessThan(5_000_000_000L);}
            assertThat(error.get()).isNull();assertThat(commandCount.get()).isEqualTo(cancelling?2:1);assertThat(sessionRows.findById(f.robotId).orElseThrow().isHandshakeAcked()).isTrue();
            // MQTT 5 broker rejects a robot credential attempting a different robot's topic.
            boolean denied=false;
            try {var token=pi.publish(MqttTopics.topic(UUID.randomUUID(),"events"),"{}".getBytes(StandardCharsets.UTF_8),1,false);token.waitForCompletion(5000);if(token.getReasonCodes()!=null) for(int code:token.getReasonCodes()) if(code>=128) denied=true;}
            catch(MqttException ex) {denied=true;}
            assertThat(denied || Files.readString(log).contains("Denied PUBLISH")).isTrue();
        } finally {
            tick.shutdownNow();tick.awaitTermination(3,TimeUnit.SECONDS);properties.setEnabled(false);properties.setDispatchEnabled(false);
            if(pi!=null) {try {pi.disconnectForcibly(1000L,1000L,true);pi.close(true);}catch(Exception ignored) { }}transport.stop();
            new ProcessBuilder("wsl","-d","Ubuntu-22.04","--","env","MQTT_TEST_BROKER_HOME="+home,"sh",script,"stop",Integer.toString(port)).start().waitFor(5,TimeUnit.SECONDS);
            broker.destroy();properties.getRobotIds().remove(f.robotId);tx.executeWithoutResult(status->MqttTestFixture.cleanup(em,f));
        }
    }
    private static void await(BooleanSupplier condition,long millis) throws Exception {
        long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        while(!condition.getAsBoolean() && System.nanoTime()<end) Thread.sleep(25);
        assertThat(condition.getAsBoolean()).as("Condition within %sms",millis).isTrue();
    }
}
