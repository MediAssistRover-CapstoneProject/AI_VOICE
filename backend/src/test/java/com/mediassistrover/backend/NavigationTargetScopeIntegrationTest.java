package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.service.*;
import com.mediassistrover.backend.security.DevicePrincipal;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Transactional
class NavigationTargetScopeIntegrationTest extends MySqlIntegrationTest {
    @Autowired EntityManager em;@Autowired ObjectMapper json;@Autowired NavigationTargetService targets;@Autowired NavigationProfileService profiles;
    MqttTestFixture f;Job job;JobStep resolve;EndpointGroup group;Endpoint first,second;ArrayNode list;
    void fixture(){f=MqttTestFixture.create(em,json,StepType.MOVE,null);job=em.find(Job.class,f.jobId);job.setStatus(JobStatus.RUNNING);job.setStartedAt(Instant.now());
        first=em.find(Endpoint.class,f.endpointId);second=Endpoint.builder().map(job.getMap()).endpointCode("S-"+UUID.randomUUID()).endpointName("second").endpointType(EndpointType.WAYPOINT).xM(BigDecimal.TEN).yM(BigDecimal.ONE).yawRad(BigDecimal.ZERO).active(true).accessible(true).build();em.persist(second);
        group=EndpointGroup.builder().map(job.getMap()).groupCode("G-"+UUID.randomUUID()).groupName("group").selectionPolicy(SelectionPolicy.PRIORITY).allowFallback(true).active(true).build();em.persist(group);
        int i=0;for(Endpoint e:List.of(first,second))em.persist(EndpointGroupMember.builder().id(new EndpointGroupMemberId(group.getEndpointGroupId(),e.getEndpointId())).endpointGroup(group).endpoint(e).map(job.getMap()).sequenceNo(++i).active(true).build());
        resolve=em.find(JobStep.class,f.piStepId);resolve.setStatus(JobStepStatus.RUNNING);resolve.setSelectedAt(Instant.now());resolve.setStartedAt(Instant.now());resolve.setExecutionSequenceNo(10);resolve.getStep().setStepType(StepType.DATA_SERVICE);
        resolve.setStepTypeSnapshot("DATA_SERVICE");resolve.setResolvedInputsJson(json.createObjectNode().put("service_key","RESOLVE_TARGETS").put("endpoint_group_id",group.getEndpointGroupId().toString()));job.setSelectedJobStep(resolve);em.flush();}
    JobStep next(String code,StepType type,long seq,ObjectNode input){Step definition=Step.builder().workflow(job.getWorkflow()).task(resolve.getStep().getTask()).stepCode(code).stepName(code).stepType(type).sequenceNo((int)seq).enabled(true)
            .paramsJson(input.deepCopy()).inputMappingJson(json.createObjectNode()).timeoutSec(60).maxAttempts(1).maxVisits(3).retryCodesJson(json.createArrayNode()).auditLevel(StepAuditLevel.FULL).build();em.persist(definition);
        JobStep visit=JobStep.builder().jobTask(resolve.getJobTask()).step(definition).stepCodeSnapshot(code).stepTypeSnapshot(type.name()).executionSequenceNo(seq).visitNo(1).attemptNo(1).timeoutSecSnapshot(60).status(JobStepStatus.RUNNING)
                .selectedAt(Instant.now()).startedAt(Instant.now()).resolvedInputsJson(input).operationKey(job.getJobId()+":"+code+":1").build();em.persist(visit);job.setSelectedJobStep(visit);em.flush();return visit;}
    JobStep selector(){list=targets.resolve(resolve,group.getEndpointGroupId());resolve.setStatus(JobStepStatus.SUCCEEDED);resolve.setResultJson(json.createObjectNode().set("data",json.createObjectNode().set("targets",list)));
        return next("SEL",StepType.DATA_SERVICE,20,json.createObjectNode().put("service_key","SELECT_TARGET").set("targets",list));}
    @Test void filtersBothEndpointAccessibilityAndMembershipState(){fixture();second.setAccessible(false);assertThat(targets.resolve(resolve,group.getEndpointGroupId())).hasSize(1);
        second.setAccessible(true);em.find(EndpointGroupMember.class,new EndpointGroupMemberId(group.getEndpointGroupId(),first.getEndpointId())).setActive(false);
        assertThat(targets.resolve(resolve,group.getEndpointGroupId()).get(0).path("endpoint_id").asText()).isEqualTo(second.getEndpointId().toString());}
    @Test void databaseRejectsCorruptCrossMapMembership(){fixture();MqttTestFixture other=MqttTestFixture.create(em,json,StepType.MOVE,null);FacilityMap otherMap=em.find(FacilityMap.class,other.mapId);
        em.find(EndpointGroupMember.class,new EndpointGroupMemberId(group.getEndpointGroupId(),first.getEndpointId())).setMap(otherMap);
        assertThatThrownBy(()->targets.resolve(resolve,group.getEndpointGroupId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);}
    @Test void rejectsAGroupFromAnotherMap(){fixture();MqttTestFixture other=MqttTestFixture.create(em,json,StepType.MOVE,null);
        EndpointGroup foreign=EndpointGroup.builder().map(em.find(FacilityMap.class,other.mapId)).groupCode("F-"+UUID.randomUUID()).groupName("foreign").selectionPolicy(SelectionPolicy.PRIORITY).active(true).build();em.persist(foreign);
        assertThatThrownBy(()->targets.resolve(resolve,foreign.getEndpointGroupId())).hasMessageContaining("outside");}
    @Test void nearestPolicyUsesFreshSameMapPoseAndDoesNotClaimPathReachability(){fixture();group.setSelectionPolicy(SelectionPolicy.NEAREST_REACHABLE);
        em.persist(RobotLocationHistory.builder().robotLocationHistoryId(UUID.randomUUID()).robot(job.getRobot()).map(job.getMap()).xM(BigDecimal.TEN).yM(BigDecimal.ONE).yawRad(BigDecimal.ZERO).fresh(true).capturedAt(Instant.now()).receivedAt(Instant.now()).build());em.flush();
        assertThat(targets.resolve(resolve,group.getEndpointGroupId()).get(0).path("endpoint_id").asText()).isEqualTo(second.getEndpointId().toString());}
    @Test void nearestPolicyRejectsStalePose(){fixture();group.setSelectionPolicy(SelectionPolicy.NEAREST_REACHABLE);
        em.persist(RobotLocationHistory.builder().robotLocationHistoryId(UUID.randomUUID()).robot(job.getRobot()).map(job.getMap()).xM(BigDecimal.TEN).yM(BigDecimal.ONE).yawRad(BigDecimal.ZERO).fresh(true).capturedAt(Instant.now().minusSeconds(5)).receivedAt(Instant.now()).build());em.flush();
        assertThatThrownBy(()->targets.resolve(resolve,group.getEndpointGroupId())).hasMessageContaining("fresh pose");}
    @Test void rejectsInjectedTargetsAndChangedCoordinates(){fixture();JobStep selector=selector();ArrayNode injected=list.deepCopy();((ObjectNode)injected.get(0)).put("x_m",999);
        assertThatThrownBy(()->targets.select(selector,injected,0)).hasMessageContaining("snapshot");
        first.setXM(new BigDecimal("9"));assertThatThrownBy(()->targets.select(selector,list,0)).hasMessageContaining("changed after");}
    @Test void checksAreScopedToTheJobMap(){fixture();MqttTestFixture other=MqttTestFixture.create(em,json,StepType.MOVE,null);
        assertThat(targets.check(resolve,other.endpointId).path("is_valid").asBoolean()).isFalse();first.setAccessible(false);
        assertThat(targets.check(resolve,first.getEndpointId()).path("is_valid").asBoolean()).isFalse();}
    @Test void oneSelectionCannotAuthorizeTwoMoveVisits(){fixture();JobStep selector=selector();var selected=targets.select(selector,list,0);selector.setStatus(JobStepStatus.SUCCEEDED);selector.setResultJson(json.createObjectNode().set("data",selected));
        ObjectNode input=json.createObjectNode().put("map_id",job.getMap().getMapId().toString()).put("endpoint_id",first.getEndpointId().toString()).put("mode","NORMAL").put("max_speed_mps",0.05);
        JobStep move=next("MOVE1",StepType.MOVE,30,input);move.getStep().setInputMappingJson(json.createObjectNode().set("endpoint_id",json.createObjectNode().put("$ref","/outputs/SEL/data/endpoint_id")));targets.bindMove(move,input);
        JobStep another=next("MOVE2",StepType.MOVE,40,input.deepCopy());another.getStep().setInputMappingJson(move.getStep().getInputMappingJson().deepCopy());
        assertThatThrownBy(()->targets.bindMove(another,input)).hasMessageContaining("another MOVE");}
    @Test void groupDestinationCannotBypassSelectionWithClientEndpointOrContext(){fixture();job.setInputJson(json.createObjectNode().put("target_mode","ENDPOINT_GROUP").put("endpoint_group_id",group.getEndpointGroupId().toString()));
        JobStep move=next("MOVE",StepType.MOVE,30,json.createObjectNode().put("map_id",f.mapId.toString()).put("endpoint_id",f.endpointId.toString()));
        move.getStep().setInputMappingJson(json.createObjectNode().set("endpoint_id",json.createObjectNode().put("$ref","/vars/endpoint_id")));
        assertThatThrownBy(()->targets.bindMove(move,move.getResolvedInputsJson())).hasMessageContaining("SELECT_TARGET");}
    @Test void frozenCandidateOrderCannotBeSkipped(){fixture();JobStep selector=selector();assertThatThrownBy(()->targets.select(selector,list,1)).hasMessageContaining("advance once");}
    @Test void zoneCatalogKeepsMapFrameAndOmitsInactiveZones(){fixture();ArrayNode polygon=json.createArrayNode().addArray().add(0).add(0);polygon.addArray().add(1).add(0);polygon.addArray().add(0).add(1);
        Zone zone=Zone.builder().map(job.getMap()).zoneCode("Z").zoneName("Zone").zoneType(ZoneType.KEEP_OUT).polygonJson(polygon).active(true).build();em.persist(zone);em.flush();
        DevicePrincipal device=new DevicePrincipal(job.getRobot().getRobotId(),job.getRobot().getRobotCode(),0,"test",Instant.now().plusSeconds(300));var config=profiles.zoneConfiguration(f.mapId,device);
        assertThat(config.path("config").path("frame_id").asText()).isEqualTo(job.getMap().getFrameId());assertThat(config.path("config").path("zones").get(0).path("polygon")).isEqualTo(polygon);
        zone.setActive(false);assertThat(profiles.zoneConfiguration(f.mapId,device).path("config").path("zones")).isEmpty();
        assertThatThrownBy(()->profiles.zoneConfiguration(UUID.randomUUID(),device)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);}
}
