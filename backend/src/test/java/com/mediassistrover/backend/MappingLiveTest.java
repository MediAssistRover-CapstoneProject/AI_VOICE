package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.mapping.*;
import com.mediassistrover.backend.mqtt.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.validation.MqttContractValidator;
import org.junit.jupiter.api.*;
import org.springframework.context.ApplicationEventPublisher;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.io.*;
import java.nio.file.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.zip.DeflaterOutputStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MappingLiveTest {
    final UUID robot=UUID.fromString("11111111-1111-4111-8111-111111111111"),sid=UUID.fromString("22222222-2222-4222-8222-222222222222"),
            jobId=UUID.fromString("33333333-3333-4333-8333-333333333333"),tid=UUID.fromString("44444444-4444-4444-8444-444444444444");
    final Instant now=Instant.parse("2026-10-14T10:00:00Z");final Clock clock=Clock.fixed(now,ZoneOffset.UTC);
    final MqttWire wire=new MqttWire();final MqttContractValidator contracts=new MqttContractValidator();
    final JobRepository jobs=mock(JobRepository.class);final MappingSessionRepository sessions=mock(MappingSessionRepository.class);
    final RobotMqttSessionRepository links=mock(RobotMqttSessionRepository.class);final MqttSessionRegistry registry=mock(MqttSessionRegistry.class);
    final MqttProperties mqtt=new MqttProperties();final MappingProperties properties=new MappingProperties();
    final PahoMqttTransport transport=mock(PahoMqttTransport.class);
    final MappingControlService controls=new MappingControlService(jobs,sessions,links,registry,mqtt,properties,transport,wire,contracts,clock);
    final MappingPreviewService previews=new MappingPreviewService(sessions,clock,mock(ApplicationEventPublisher.class));
    MappingSession session;Job job;RobotMqttSession link;ObjectNode published;
    @BeforeEach void setup() {
        properties.setEnabled(true);mqtt.setEnabled(true);mqtt.setDispatchEnabled(true);mqtt.setRobotIds(Set.of(robot));
        session=MappingSession.builder().sessionId(sid).jobId(jobId).robotId(robot).state("MAPPING").potentiallyOpen(true).build();
        job=Job.builder().jobId(jobId).status(JobStatus.RUNNING).selectedJobStep(JobStep.builder().status(JobStepStatus.RUNNING).stepTypeSnapshot("WAIT")
                .resolvedInputsJson(wire.object().put("event_type","MAPPING_SAVE_REQUESTED")).build()).build();
        link=new RobotMqttSession();link.setTransportSessionId(tid);link.setProducerSessionId(UUID.randomUUID());link.setHelloJson(wire.object().put("mappingEnabled",true));
        when(jobs.lockById(jobId)).thenReturn(Optional.of(job));when(sessions.findByJobId(jobId)).thenReturn(Optional.of(session));
        when(sessions.findById(sid)).thenReturn(Optional.of(session));when(links.findById(robot)).thenReturn(Optional.of(link));
        when(registry.fresh(link)).thenReturn(true);when(transport.connected()).thenReturn(true);
        when(transport.publish(anyString(),any(),eq(0))).thenAnswer(i->{published=wire.parse(i.getArgument(1));return CompletableFuture.completedFuture(null);});
    }
    @Test void publishesBoundedControlAndCorrelatesOnlyCurrentPiAck() throws Exception {
        var sent=controls.send(jobId,new MappingControlService.Request(sid,.05,0.,400));
        verify(transport).publish(eq("mar/robots/"+robot+"/mapping/control"),any(),eq(0));contracts.live(published);
        assertThat(controls.status(jobId).status()).isEqualTo("MQTT_PUBLISHED");
        Files.createDirectories(Path.of("target/mapping-contract"));Files.writeString(Path.of("target/mapping-contract/be-control.json"),wire.json(published));
        ObjectNode ack=published.deepCopy();ack.remove(List.of("issuedAt","expiresAt","linearX","angularZ","durationMs"));
        ack.put("messageType","MAPPING_CONTROL_ACK").put("producerSessionId",link.getProducerSessionId().toString()).put("accepted",true).putNull("errorCode").put("receivedAt",now.toString());contracts.live(ack);
        ack.put("transportSessionId",UUID.randomUUID().toString());controls.acknowledge(robot,ack);assertThat(controls.status(jobId).status()).isEqualTo("MQTT_PUBLISHED");
        ack.put("transportSessionId",tid.toString());controls.acknowledge(robot,ack);assertThat(controls.status(jobId).status()).isEqualTo("PI_ACCEPTED");
        assertThat(sent.expiresAt()).isEqualTo(now.plusMillis(1500));
    }
    @Test void rejectsMoveOutsideWaitAndStopRemainsAvailableDuringSave() {
        session.setState("SAVING");job.getSelectedJobStep().setStepTypeSnapshot("EXECUTE_SENSING");
        assertThatThrownBy(()->controls.send(jobId,new MappingControlService.Request(sid,.05,0.,400))).hasMessageContaining("Move only");
        assertThatCode(()->controls.send(jobId,new MappingControlService.Request(sid,0.,0.,400))).doesNotThrowAnyException();
    }
    @Test void realtimeJavaClockSerializesControlWithMillisecondPrecision() {
        var nanos=Clock.fixed(Instant.parse("2026-10-20T15:30:40.225096447Z"),ZoneOffset.UTC);
        var service=new MappingControlService(jobs,sessions,links,registry,mqtt,properties,transport,wire,contracts,nanos);
        var receipt=service.send(jobId,new MappingControlService.Request(sid,.05,0.,400));
        assertThat(receipt.issuedAt()).isEqualTo(Instant.parse("2026-10-20T15:30:40.225Z"));
        assertThat(receipt.expiresAt()).isEqualTo(Instant.parse("2026-10-20T15:30:41.725Z"));
        assertThat(published.path("issuedAt").asText()).isEqualTo("2026-10-20T15:30:40.225Z");
        contracts.live(published);
    }
    @Test void rejectsDisconnectedForeignSessionAndOutOfRangeVelocity() {
        assertThatThrownBy(()->controls.send(jobId,new MappingControlService.Request(UUID.randomUUID(),.05,0.,400))).hasMessageContaining("does not belong");
        for(double speed:new double[]{-.31,.31,Double.NaN,Double.POSITIVE_INFINITY})
            assertThatThrownBy(()->controls.send(jobId,new MappingControlService.Request(sid,speed,0.,400))).hasMessageContaining("Invalid");
        when(registry.fresh(link)).thenReturn(false);
        assertThatThrownBy(()->controls.send(jobId,new MappingControlService.Request(sid,.05,0.,400))).hasMessageContaining("not ready");
        verify(transport,never()).publish(anyString(),any(),anyInt());
    }
    @Test void limitsRepeatedMoveButAllowsImmediateStop() {
        controls.send(jobId,new MappingControlService.Request(sid,.05,0.,400));
        assertThatThrownBy(()->controls.send(jobId,new MappingControlService.Request(sid,.05,0.,400))).hasMessageContaining("250 ms");
        controls.send(jobId,new MappingControlService.Request(sid,0.,0.,400));assertThat(published.path("linearX").asDouble()).isZero();
    }
    @Test void jogEnvelopeAllowsBoundedSpeedAndSixHundredMsLease() {
        controls.send(jobId,new MappingControlService.Request(sid,.08,.25,600));
        contracts.live(published);
        assertThat(published.path("linearX").asDouble()).isEqualTo(.08);
        assertThat(published.path("durationMs").asInt()).isEqualTo(600);
        assertThatThrownBy(()->controls.send(jobId,new MappingControlService.Request(sid,.08,0.,4000)))
                .hasMessageContaining("Invalid");
    }
    @Test void mecanumReverseAndFasterTurnAreSignedAndHaveBoundedLease() {
        controls.send(jobId,new MappingControlService.Request(sid,-.10,-.45,900));
        contracts.live(published);
        assertThat(published.path("linearX").asDouble()).isEqualTo(-.1);
        assertThat(published.path("angularZ").asDouble()).isEqualTo(-.45);
        assertThat(published.path("durationMs").asInt()).isEqualTo(900);
        assertThatThrownBy(()->controls.send(jobId,new MappingControlService.Request(sid,0.,1.01,900))).hasMessageContaining("Invalid");
        assertThatThrownBy(()->controls.send(jobId,new MappingControlService.Request(sid,0.,.45,901))).hasMessageContaining("Invalid");
    }
    @Test void bareMecanumUpperEnvelopeUsesTheSameWireContract() {
        controls.send(jobId,new MappingControlService.Request(sid,.30,1.0,900));
        contracts.live(published);
        assertThat(published.path("linearX").asDouble()).isEqualTo(.30);
        assertThat(published.path("angularZ").asDouble()).isEqualTo(1.0);
    }
    @Test void localGamepadRejectsWebMoveButKeepsStopAvailable() {
        var report=wire.object();report.putObject("autonomy").putObject("manual_control").put("source","gamepad");
        link.setHeartbeatJson(wire.object().set("mappingSession",report));
        assertThatThrownBy(()->controls.send(jobId,new MappingControlService.Request(sid,.1,0.,900)))
                .hasMessageContaining("Local gamepad owns");
        controls.send(jobId,new MappingControlService.Request(sid,0.,0.,900));
        assertThat(published.path("linearX").asDouble()).isZero();
    }
    @Test void stopSequencePreventsAnOlderHttpMoveArrivingAfterStop() {
        UUID ui=UUID.randomUUID();
        controls.send(jobId,new MappingControlService.Request(sid,0.,0.,600,ui,2L,now));
        assertThatThrownBy(()->controls.send(jobId,new MappingControlService.Request(sid,.08,0.,600,ui,1L,now)))
                .hasMessageContaining("Stale UI control sequence");
        assertThat(published.path("linearX").asDouble()).isZero();
        verify(transport,times(1)).publish(anyString(),any(),eq(0));
    }
    @Test void staleBrowserRequestCannotStartMotion() {
        assertThatThrownBy(()->controls.send(jobId,new MappingControlService.Request(sid,.08,0.,600,UUID.randomUUID(),1L,now.minusSeconds(2))))
                .hasMessageContaining("Stale UI control request");
        verify(transport,never()).publish(anyString(),any(),anyInt());
    }
    void enableAutonomy() {
        link.setLastHeartbeatAt(now);
        var report=wire.object().put("session_id",sid.toString());
        report.set("autonomy",wire.object().put("available",true));
        link.setHeartbeatJson(wire.object().set("mappingSession",report));
    }
    MappingControlService.AutonomyRequest auto(String action) {
        return new MappingControlService.AutonomyRequest(sid,new MappingControlService.Autonomy(action,
                UUID.fromString("55555555-5555-4555-8555-555555555555"),180,false),UUID.randomUUID(),1L,now);
    }
    @Test void autonomyUsesSameScopedShortLeaseAndStrictWireContract() {
        enableAutonomy();controls.sendAutonomy(jobId,auto("START"));contracts.live(published);
        assertThat(published.path("linearX").asDouble()).isZero();
        assertThat(published.path("durationMs").asInt()).isEqualTo(900);
        assertThat(published.path("autonomy").path("action").asText()).isEqualTo("START");
        assertThat(published.path("autonomy").path("maxDurationSec").asInt()).isEqualTo(180);
    }
    @Test void autonomyZeroVelocityDoesNotBypassWaitPhaseAndOldPiCannotStart() {
        assertThatThrownBy(()->controls.sendAutonomy(jobId,auto("START"))).hasMessageContaining("not configured");
        enableAutonomy();job.setStatus(JobStatus.RECOVERY_REQUIRED);
        assertThatThrownBy(()->controls.sendAutonomy(jobId,auto("START"))).hasMessageContaining("Move only");
        assertThatThrownBy(()->controls.sendAutonomy(jobId,auto("RENEW"))).hasMessageContaining("Move only");
        controls.sendAutonomy(jobId,auto("STOP"));contracts.live(published);
    }
    @Test void autonomyRejectsForeignHeartbeatScopeAndDoesNotRelaxManualLane() {
        enableAutonomy();((ObjectNode)link.getHeartbeatJson().path("mappingSession")).put("session_id",UUID.randomUUID().toString());
        assertThatThrownBy(()->controls.sendAutonomy(jobId,auto("START"))).hasMessageContaining("not configured");
        controls.send(jobId,new MappingControlService.Request(sid,.05,0.,400));
        assertThat(published.has("autonomy")).isFalse();
    }
    ObjectNode map(byte[] grid) throws Exception {
        var bytes=new ByteArrayOutputStream();try(var zip=new DeflaterOutputStream(bytes)){zip.write(grid);}
        return wire.object().put("mapping_session_id",sid.toString()).put("captured_at",now.toString()).put("revision",1)
                .put("width",2).put("height",2).put("data",Base64.getEncoder().encodeToString(bytes.toByteArray()));
    }
    @Test void mapPngFlipsRosRowsAndRejectsCorruptCellsAndStaleFrames() throws Exception {
        var frame=map(new byte[]{0,100,-1,50});previews.accept(robot,frame);
        var image=ImageIO.read(new ByteArrayInputStream(previews.mapImage(sid).bytes()));
        assertThat(image.getRaster().getSample(0,1,0)).isEqualTo(254);assertThat(image.getRaster().getSample(1,1,0)).isZero();
        assertThat(image.getRaster().getSample(0,0,0)).isEqualTo(205);
        var invalid=map(new byte[]{0,101,0,0}).put("revision",2).put("captured_at",now.plusMillis(1).toString());
        assertThatThrownBy(()->previews.accept(robot,invalid)).hasMessageContaining("Invalid occupancy");
        session.setPotentiallyOpen(false);assertThat(previews.mapImage(sid)).isNull();
    }
    @Test void cameraJpegRequiresMatchingDimensionsAndSession() throws Exception {
        var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(4,3,BufferedImage.TYPE_INT_RGB),"jpeg",bytes);
        var payload=wire.object().put("mapping_session_id",sid.toString()).put("captured_at",now.toString()).put("revision",1)
                .put("width",4).put("height",3).put("encoding","jpeg-base64").put("data",Base64.getEncoder().encodeToString(bytes.toByteArray()));
        var envelope=wire.object().put("messageType","CAMERA_PREVIEW").put("robotId",robot.toString()).put("transportSessionId",tid.toString());envelope.set("payload",payload);contracts.live(envelope);
        previews.acceptCamera(robot,payload);assertThat(previews.cameraImage(sid).bytes()).isEqualTo(bytes.toByteArray());
        payload.put("width",5);assertThatThrownBy(()->previews.acceptCamera(robot,payload)).hasMessageContaining("dimensions");
        payload.put("width",4).put("captured_at",now.minusSeconds(20).toString()).put("revision",2);previews.acceptCamera(robot,payload);
        assertThat(previews.cameraImage(sid).revision()).isEqualTo(1);
    }
    @Test void metadataKeepsPngGeometryAndPoseWithoutSendingRasterOrLeakingAcrossSessions() throws Exception {
        var payload=map(new byte[]{0,100,-1,50}).put("resolution",.025).put("frame_id","map");
        payload.putArray("origin").add(-2.).add(-1.).add(.3);
        payload.set("pose",wire.object().put("fresh",true).put("age_sec",.1).set("value",wire.object().put("x_m",0).put("y_m",0).put("yaw_rad",.5)));
        previews.accept(robot,payload);
        var meta=previews.metadata(sid);
        assertThat(meta.has("data")).isFalse();assertThat(meta.path("width_m").asDouble()).isEqualTo(.05);
        assertThat(meta.path("height_m").asDouble()).isEqualTo(.05);
        assertThat(meta.path("pose")).isEqualTo(payload.path("pose"));assertThat(meta.path("origin")).isEqualTo(payload.path("origin"));
        assertThat(previews.latest(sid).has("data")).isTrue();
        assertThat(previews.metadata(UUID.randomUUID())).isNull();
        session.setPotentiallyOpen(false);assertThat(previews.metadata(sid)).isNull();
    }
    @Test void actualPythonPreviewMatchesJavaSchemaAndRendersPng() throws Exception {
        String fixture=System.getenv("MAR_PI_PREVIEW_FIXTURE");
        org.junit.jupiter.api.Assumptions.assumeTrue(fixture!=null,"Set MAR_PI_PREVIEW_FIXTURE to Python MappingManager output");
        ObjectNode envelope=wire.parse(Files.readAllBytes(Path.of(fixture)));
        contracts.inbound(envelope);
        assertThat(envelope.path("payload").path("pose").path("value").size()).isEqualTo(3);
        var payload=(ObjectNode)envelope.path("payload");payload.put("captured_at",now.toString());
        previews.accept(robot,payload);
        assertThat(ImageIO.read(new ByteArrayInputStream(previews.mapImage(sid).bytes())).getWidth()).isEqualTo(2);
    }
}
