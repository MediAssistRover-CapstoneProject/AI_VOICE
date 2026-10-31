package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.mapping.*;
import com.mediassistrover.backend.mqtt.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.DevicePrincipal;
import com.mediassistrover.backend.service.*;
import com.mediassistrover.backend.validation.MqttContractValidator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Transactional
class NavigationSessionIntegrationTest extends MySqlIntegrationTest {
    @Autowired jakarta.persistence.EntityManager em;@Autowired ObjectMapper json;
    @Autowired NavigationSessionService sessions;@Autowired NavigationProfileService profiles;
    @Autowired RobotNavigationSessionRepository sessionRows;@Autowired RobotRepository robots;
    @Autowired MapBundleService bundles;@Autowired MappingProperties mapping;@Autowired FacilityMapRepository maps;
    @Autowired MqttSessionRegistry registry;@Autowired MqttConnectionState connection;@Autowired MqttWire wire;
    @Autowired MqttContractValidator contracts;@Autowired RobotRealtimeSnapshotService snapshots;
    @Autowired MqttProperties mqtt;
    @Autowired EndpointRepository endpoints;
    @Autowired ServiceRequestService requests;@Autowired ServiceRequestRepository requestRows;
    @Autowired JobExecutionService execution;
    @Autowired MqttStepDispatchService dispatch;@Autowired RobotCommandOutboxRepository outbox;
    @TempDir Path folder;String oldStorage;MqttTestFixture f;UUID mapId;String hash;

    @BeforeEach void setup(){oldStorage=mapping.getStorageDir();mapping.setStorageDir(folder.toString());connection.connectedNow();
        f=MqttTestFixture.create(em,json,StepType.MOVE,null);
        var job=em.find(Job.class,f.jobId);job.setStatus(JobStatus.SUCCEEDED);job.setSelectedJobStep(null);
        var robot=em.find(Robot.class,f.robotId);robot.setStatus(RobotStatus.IDLE);robot.setDeviceSecretHash("test-registration-only");em.flush();
        mqtt.setEnabled(true);mqtt.setRobotIds(new java.util.HashSet<>(Set.of(f.robotId)));
        mapId=imported(false);hash=maps.findById(mapId).orElseThrow().getAssetHash();
    }
    @AfterEach void reset(){mapping.setStorageDir(oldStorage);connection.disconnected();mqtt.setEnabled(false);mqtt.getRobotIds().clear();}
    UUID imported(boolean legacy){
        String yaml="image: map.pgm\nmode: trinary\nresolution: 0.1\norigin: [0,0,0]\nnegate: 0\noccupied_thresh: 0.65\nfree_thresh: "+(legacy?"0.25":"0.196")+"\n";
        byte[] header="P5\n20 20\n255\n".getBytes(),pgm=Arrays.copyOf(header,header.length+400);Arrays.fill(pgm,header.length,pgm.length,(byte)254);
        pgm[header.length+399]=(byte)205;
        var result=bundles.importMap(f.floorId,"SESSION_"+UUID.randomUUID(),1,"Navigation session map",yaml.getBytes(),pgm);
        maps.findById(result.mapId()).orElseThrow().setStatus(MapStatus.ACTIVE);em.flush();return result.mapId();
    }
    DevicePrincipal device(){var r=robots.findById(f.robotId).orElseThrow();return new DevicePrincipal(f.robotId,r.getRobotCode(),r.getDeviceTokenVersion(),"test",Instant.now().plusSeconds(900));}
    ObjectNode context(JsonNode created){return wire.object().put("session_id",created.path("sessionId").asText()).put("map_id",mapId.toString()).put("map_asset_hash",hash).put("expires_at",created.path("expiresAt").asText());}
    ObjectNode open(){return sessions.create(f.robotId,new NavigationSessionService.Create(mapId,900));}
    void connect(ObjectNode created){var hello=f.hello(json);((ObjectNode)hello.path("payload")).set("navigationSession",context(created));contracts.inbound(hello);
        registry.hello(wire.envelope(hello),List.of());em.find(RobotMqttSession.class,f.robotId).setHandshakeAcked(true);em.flush();}
    void heartbeat(ObjectNode created,boolean ready,long revision){var message=f.heartbeat(json);var p=(ObjectNode)message.path("payload");
        p.put("currentMapId",mapId.toString()).put("mapAssetHash",hash);p.set("navigationSession",context(created));
        var telemetry=(ObjectNode)p.path("telemetry");var pose=telemetry.putObject("map_pose").put("age_sec",0.).put("fresh",ready);
        if(ready)pose.putObject("value").put("map_id",mapId.toString()).put("x_m",1.).put("y_m",1.).put("yaw_rad",0.)
                .put("navigation_session_id",created.path("sessionId").asText()).put("initialization_revision",revision);else pose.putNull("value");
        telemetry.putObject("localization").put("ready",ready).put("reason",ready?"LOCALIZED":"VERIFYING").put("session_id",created.path("sessionId").asText())
                .put("initialization_revision",revision).put("map_id",mapId.toString()).put("map_asset_hash",hash).put("age_sec",.1);
        contracts.inbound(message);registry.heartbeat(wire.envelope(message));em.flush();
    }
    @Test void openingMapDoesNotInventPoseAndDeviceCanOnlyDownloadItsGrantedMap(){
        UUID before=robots.findById(f.robotId).orElseThrow().getCurrentMap().getMapId();var opened=open();
        assertThat(robots.findById(f.robotId).orElseThrow().getCurrentMap().getMapId()).isEqualTo(before);
        assertThat(opened.path("initialization").path("mode").asText()).isEqualTo("MANUAL");
        assertThat(opened.path("state").asText()).isEqualTo("WAITING_FOR_PI");assertThat(bundles.bundle(mapId,device())).isNotEmpty();
        UUID other=imported(false);assertThatThrownBy(()->bundles.bundle(other,device())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void localizationRequiresCurrentRevisionAndEndpointEditsRequireNewProfileAck(){
        var opened=open();connect(opened);heartbeat(opened,false,0);
        UUID id=UUID.fromString(opened.path("sessionId").asText());
        sessions.initialize(f.robotId,new NavigationSessionService.Initialize(id,"SEED",1.,1.,0.));
        heartbeat(opened,true,1);assertThat(sessions.status(f.robotId).path("state").asText()).isEqualTo("READY");
        assertThat(snapshots.snapshot(f.robotId).latestPose().fresh()).isTrue();
        var endpoint=endpoints.saveAndFlush(Endpoint.builder().map(maps.findById(mapId).orElseThrow()).endpointCode("P1").endpointName("Point")
                .endpointType(EndpointType.WAYPOINT).xM(java.math.BigDecimal.ONE).yM(java.math.BigDecimal.ONE).yawRad(java.math.BigDecimal.ZERO).active(true).accessible(true).build());
        var manifest=profiles.manifest(device());profiles.confirm(new NavigationProfileService.Confirm(f.transportId,f.producerId,mapId,hash,manifest.profileHash()),device());
        var link=em.find(RobotMqttSession.class,f.robotId);var params=wire.object().put("map_id",mapId.toString()).put("endpoint_id",endpoint.getEndpointId().toString());
        assertThat(profiles.ready(robots.findById(f.robotId).orElseThrow(),link,params)).isEqualTo(manifest.profileHash());
        endpoint.setYawRad(new java.math.BigDecimal("1.0"));em.flush();
        assertThat(profiles.ready(robots.findById(f.robotId).orElseThrow(),link,params)).isNull();
        sessions.initialize(f.robotId,new NavigationSessionService.Initialize(id,"GLOBAL",null,null,null));
        // An in-flight old heartbeat after reseeding must not resurrect the old pose or close transport.
        heartbeat(opened,true,1);
        assertThat(sessions.status(f.robotId).path("state").asText()).isEqualTo("LOCALIZING");
        assertThat(snapshots.snapshot(f.robotId).latestPose().fresh()).isFalse();
    }
    @Test void seedMustBeFreeAndCannotPretendUnknownIsFree(){var opened=open();UUID id=UUID.fromString(opened.path("sessionId").asText());
        assertThatThrownBy(()->sessions.initialize(f.robotId,new NavigationSessionService.Initialize(id,"SEED",1.95,.05,0.))).hasMessageContaining("known free");}
    @Test void processRestartCannotReuseOldSessionAndClosingAllowsAnotherMap(){
        var opened=open();connect(opened);var row=sessionRows.findById(f.robotId).orElseThrow();
        assertThatThrownBy(()->sessions.hello(f.robotId,UUID.randomUUID(),context(opened))).hasMessageContaining("PROCESS_CHANGED");
        sessions.close(f.robotId,row.getSessionId());mapId=imported(false);hash=maps.findById(mapId).orElseThrow().getAssetHash();
        assertThat(open().path("mapId").asText()).isEqualTo(mapId.toString());
    }
    @Test void legacyTrinaryMapGetsNewIdAndHashWithoutModifyingOriginal(){
        UUID old=imported(true);String oldHash=maps.findById(old).orElseThrow().getAssetHash();assertThat(bundles.navigationSafe(old)).isFalse();
        var copy=bundles.navigationCopy(old);assertThat(copy.mapId()).isNotEqualTo(old);assertThat(copy.assetHash()).isNotEqualTo(oldHash);
        assertThat(bundles.navigationSafe(copy.mapId())).isTrue();assertThat(maps.findById(old).orElseThrow().getAssetHash()).isEqualTo(oldHash);
    }
    @Test void heldJobPreventsClosingOrChangingMap(){var opened=open();em.find(Job.class,f.jobId).setStatus(JobStatus.RUNNING);em.flush();
        assertThatThrownBy(()->sessions.close(f.robotId,UUID.fromString(opened.path("sessionId").asText()))).hasMessageContaining("current Job");}

    @Test void navigationRequestPinsRobotAndSessionAndRejectsLostLocalization(){
        var opened=open();connect(opened);UUID session=UUID.fromString(opened.path("sessionId").asText());
        sessions.initialize(f.robotId,new NavigationSessionService.Initialize(session,"SEED",1.,1.,0.));heartbeat(opened,true,1);
        var endpoint=endpoints.saveAndFlush(Endpoint.builder().map(maps.findById(mapId).orElseThrow()).endpointCode("REQ_POINT").endpointName("Point")
                .endpointType(EndpointType.WAYPOINT).xM(java.math.BigDecimal.ONE).yM(java.math.BigDecimal.ONE).yawRad(java.math.BigDecimal.ZERO).active(true).accessible(true).build());
        var actor=com.mediassistrover.backend.security.AccountPrincipal.fromAccount(em.find(Account.class,f.accountId));
        var input=new com.mediassistrover.backend.dto.request.request.ServiceRequestRequests.NavigationCreateRequest(mapId,UUID.fromString("9d7d8f73-3f47-4a19-8e24-932f0b739a61"),null,null,endpoint.getEndpointId(),null,
                TargetMode.ENDPOINT,NavigationMode.STANDARD,null,1,null,50,null,wire.object(),f.robotId,session);
        var result=requests.createNavigation(input,"session-pin-"+UUID.randomUUID(),actor,null);
        var saved=requestRows.findById(result.request().serviceRequestId()).orElseThrow();
        assertThat(saved.getRequestedRobot().getRobotId()).isEqualTo(f.robotId);
        assertThat(saved.getRequestDataJson().path("navigation_session_id").asText()).isEqualTo(session.toString());
        assertThat(saved.getRequestDataJson().path("endpoint_id").asText()).isEqualTo(endpoint.getEndpointId().toString());
        mqtt.setDispatchEnabled(true);mqtt.setEnabledStepTypes(new HashSet<>(Set.of("MOVE")));mqtt.setAllowSimulation(true);
        try{
            var job=execution.createForRequest(saved);
            assertThat(job.getRobot().getRobotId()).isEqualTo(f.robotId);
            assertThat(job.getMap().getMapId()).isEqualTo(mapId);
            assertThat(job.getInputJson().path("endpoint_id").asText()).isEqualTo(endpoint.getEndpointId().toString());
            var manifest=profiles.manifest(device());
            profiles.confirm(new NavigationProfileService.Confirm(f.transportId,f.producerId,mapId,hash,manifest.profileHash()),device());
            JobStep move=em.createQuery("select s from JobStep s where s.jobTask.job.jobId=:id and s.stepTypeSnapshot='MOVE'",JobStep.class).setParameter("id",job.getJobId()).getSingleResult();
            job.setStatus(JobStatus.RUNNING);job.setSelectedJobStep(move);job.setStartedAt(Instant.now());
            move.setResolvedInputsJson(wire.object().put("map_id",mapId.toString()).put("endpoint_id",endpoint.getEndpointId().toString()).put("mode","NORMAL").put("max_speed_mps",.05));em.flush();
            dispatch.dispatch(move);em.flush();
            var command=outbox.findByJobStepId(move.getJobStepId()).orElseThrow().getPayloadJson();contracts.command(command);
            assertThat(command.path("navigationContext").path("session_id").asText()).isEqualTo(session.toString());
            assertThat(command.path("navigationContext").path("map_asset_hash").asText()).isEqualTo(hash);
            assertThat(command.path("navigationContext").path("initialization_revision").asLong()).isEqualTo(1);
            assertThat(command.path("navigationContext").path("profile_hash").asText()).isEqualTo(manifest.profileHash());
            job.setStatus(JobStatus.CANCELLED);em.flush();
        }finally{mqtt.setDispatchEnabled(false);mqtt.setAllowSimulation(false);mqtt.setEnabledStepTypes(new HashSet<>(Set.of("WAIT","EXECUTE_SENSING")));}
        heartbeat(opened,false,1);
        assertThatThrownBy(()->requests.createNavigation(input,"lost-location-"+UUID.randomUUID(),actor,null)).hasMessageContaining("NOT_LOCALIZED");
    }
}
