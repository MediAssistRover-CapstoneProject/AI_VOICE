package com.mediassistrover.backend.ai;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.mqtt.MqttWire;
import com.mediassistrover.backend.security.AccountPrincipal;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

/** Profile is a BE commissioning decision, not evidence that an external AI model has been deployed. */
@Service @RequiredArgsConstructor
public class AiSensingService {
    public static final Set<String> TYPES=Set.of("CROWD_DENSITY","RESTRICTED_ZONE");
    private final AiProperties properties;
    private final AiSensingProfileRepository profiles;
    private final AiObservationRepository observations;
    private final ZoneRepository zones;
    private final RobotRepository robots;
    private final AccountRepository accounts;
    private final CrowdDensityRecordRepository crowd;
    private final DetectionEventRepository detections;
    private final MqttWire wire;
    private final Clock clock;
    public record Configure(@NotNull UUID zoneId,@NotBlank @Pattern(regexp="CROWD_DENSITY|RESTRICTED_ZONE") String sensingType,
            @NotBlank @Size(max=100) String modelVersion,@NotBlank @Size(max=255) String producerRef,
            boolean enabled,boolean personPositionsEnabled,@NotBlank @Size(max=1000) String commissioningNote) { }
    public static boolean ai(JobStep step) {
        return "EXECUTE_SENSING".equals(step.getStepTypeSnapshot()) && TYPES.contains(step.getResolvedInputsJson().path("sensing_type").asText());
    }
    @Transactional public JsonNode configure(UUID robotId,Configure input,AccountPrincipal actor) {
        Account account=accounts.findById(actor.accountId()).orElseThrow();
        if(actor.role()!=Role.ADMIN || account.getRole()!=Role.ADMIN || account.getStatus()!=AccountStatus.ACTIVE || account.getTokenVersion()!=actor.tokenVersion())throw new org.springframework.security.access.AccessDeniedException("Current ADMIN session required");
        Robot robot=robots.findByIdForUpdate(robotId).orElseThrow(()->new ResourceNotFoundException("Robot not found"));
        Zone zone=zones.findById(input.zoneId()).orElseThrow(()->new ResourceNotFoundException("Zone not found"));
        if(!TYPES.contains(input.sensingType()) || input.modelVersion()==null || input.modelVersion().isBlank() || input.modelVersion().length()>100
                || input.producerRef()==null || input.producerRef().isBlank() || input.producerRef().length()>255 || input.commissioningNote()==null
                || input.commissioningNote().isBlank() || input.commissioningNote().length()>1000)fail("AI profile requires type, model, producer and commissioning note");
        requireCatalog(robot,zone,input.sensingType());
        if(input.personPositionsEnabled() && !"CROWD_DENSITY".equals(input.sensingType()))fail("Person positions require a separately commissioned crowd producer");
        var profile=profiles.findByRobotIdAndZoneIdAndSensingType(robotId,input.zoneId(),input.sensingType()).orElseGet(()->AiSensingProfile.builder().profileId(UUID.randomUUID()).robotId(robotId).zoneId(input.zoneId()).sensingType(input.sensingType()).build());
        profile.setModelVersion(input.modelVersion());profile.setProducerRef(input.producerRef());profile.setEnabled(input.enabled());profile.setPersonPositionsEnabled(input.personPositionsEnabled());
        profile.setMapAssetHash(zone.getMap().getAssetHash());profile.setFrameId(zone.getMap().getFrameId());profile.setCommissioningNote(input.commissioningNote());profile.setConfiguredBy(actor.accountId());profile.setConfiguredAt(Instant.now(clock));
        profiles.saveAndFlush(profile);return snapshot(profile,zone);
    }
    public void requireEnabled() {if(!properties.isSensingEnabled())fail("AI sensing awaits configured producer/model commissioning; keep workflow DRAFT");}
    public ObjectNode bind(JobStep step) {
        requireEnabled();Job job=step.getJobTask().getJob();JsonNode input=step.getResolvedInputsJson();
        Zone zone=zones.findById(UUID.fromString(input.path("zone_id").asText())).orElseThrow(()->new BusinessRuleException("AI zone not found"));
        requireCatalog(job.getRobot(),zone,input.path("sensing_type").asText());
        if(job.getMap()==null || !job.getMap().getMapId().equals(zone.getMap().getMapId()))fail("AI zone differs from Job map");
        var profile=profiles.findByRobotIdAndZoneIdAndSensingType(job.getRobot().getRobotId(),zone.getZoneId(),input.path("sensing_type").asText())
                .filter(AiSensingProfile::isEnabled).orElseThrow(()->new BusinessRuleException("AI producer/model profile is not commissioned for this robot/zone"));
        if(!profile.getMapAssetHash().equalsIgnoreCase(zone.getMap().getAssetHash()) || !profile.getFrameId().equals(zone.getMap().getFrameId()))fail("AI profile must be recommissioned after map/frame changes");
        if(job.getInputJson().has("model_version") && !profile.getModelVersion().equals(job.getInputJson().path("model_version").asText()))fail("Requested AI model differs from commissioned profile");
        return snapshot(profile,zone);
    }
    public boolean publicationReady(RobotCommandOutbox command) {
        if(command.getSendAttempts()>0)return true; // Resend keeps the original business body/profile.
        if(!properties.isSensingEnabled() || command.getAiProfileSnapshotJson()==null)return false;
        var n=command.getAiProfileSnapshotJson();var profile=profiles.findById(UUID.fromString(n.path("profile_id").asText())).orElse(null);
        var zone=zones.findById(UUID.fromString(n.path("zone_id").asText())).orElse(null);var robot=robots.findById(command.getRobotId()).orElse(null);
        if(profile==null || !profile.isEnabled() || zone==null || robot==null || !Objects.equals(profile.getMapAssetHash(),zone.getMap().getAssetHash())
                || !Objects.equals(profile.getFrameId(),zone.getMap().getFrameId()))return false;
        try{requireCatalog(robot,zone,profile.getSensingType());}catch(BusinessRuleException ex){return false;}
        return snapshot(profile,zone).equals(n);
    }
    public void completed(JobStep step,RobotCommandOutbox command,JsonNode output,Instant reportedAt) {
        if(!ai(step))return;
        JsonNode profile=command.getAiProfileSnapshotJson();if(profile==null)fail("AI result has no frozen producer profile");
        for(String field:Set.of("map_id","zone_id","model_version","sensing_type")) {
            boolean same=Set.of("map_id","zone_id").contains(field)?profile.path(field).asText().equalsIgnoreCase(output.path(field).asText()):profile.path(field).asText().equals(output.path(field).asText());
            if(!same)fail("AI result differs from commissioned "+field);
        }
        if(output.has("frame_id") && !profile.path("frame_id").asText().equals(output.path("frame_id").asText()))fail("AI frame differs from dispatched profile");
        if(output.has("producer_ref") && !profile.path("producer_ref").asText().equals(output.path("producer_ref").asText()))fail("AI producer differs from dispatched profile");
        Zone zone=zones.findById(UUID.fromString(profile.path("zone_id").asText())).orElseThrow();
        if(!zone.isActive() || !zone.getMap().getMapId().toString().equals(profile.path("map_id").asText())
                || !Objects.equals(zone.getMap().getAssetHash(),profile.path("map_asset_hash").asText()) || !Objects.equals(zone.getMap().getFrameId(),profile.path("frame_id").asText()))fail("AI catalog changed during operation; observation cannot be attributed to the new map");
        Instant captured=Instant.parse(output.path("captured_at").asText());
        if(captured.isBefore(reportedAt.minusSeconds(2)) || captured.isAfter(reportedAt.plusSeconds(5)))fail("AI observation is stale/future at result production");
        ObjectNode identity=wire.object().put("robot_id",command.getRobotId().toString()).put("sensing_type",output.path("sensing_type").asText())
                .put("map_id",profile.path("map_id").asText()).put("zone_id",profile.path("zone_id").asText()).put("model_version",profile.path("model_version").asText())
                .put("producer_ref",profile.path("producer_ref").asText()).put("captured_at",captured.toString());
        ObjectNode canonical=((ObjectNode)output).deepCopy();canonical.put("captured_at",captured.toString());for(String f:Set.of("map_id","zone_id"))canonical.put(f,UUID.fromString(output.path(f).asText()).toString());
        String key=wire.canonicalHash(identity),hash=wire.canonicalHash(canonical);var existing=observations.findById(key).orElse(null);
        if(existing!=null) {if(!existing.getPayloadHash().equals(hash))throw new ConflictException("AI observation identity reused with different content");return;}
        UUID id=UUID.nameUUIDFromBytes(("AI:"+key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var row=AiObservation.builder().observationKey(key).robotId(command.getRobotId()).jobStepId(step.getJobStepId()).payloadHash(hash).outputJson(output.deepCopy()).profileSnapshotJson(profile.deepCopy()).storedAt(Instant.now(clock)).build();
        String evidence=output.path("evidence_ref").isNull() || output.path("evidence_ref").isMissingNode()?null:output.path("evidence_ref").asText();
        if(evidence!=null && (evidence.length()>4000 || evidence.regionMatches(true,0,"data:",0,5)))fail("AI evidence must be a bounded reference");
        if("CROWD_DENSITY".equals(output.path("sensing_type").asText())) {
            JsonNode count=output.path("metrics").path("count");if(!count.canConvertToInt() || count.asInt()<0)fail("AI person count exceeds database range");
            crowd.saveAndFlush(CrowdDensityRecord.builder().crowdDensityRecordId(id).robot(step.getJobTask().getJob().getRobot()).zone(zone).jobStep(step)
                    .personCount(count.intValue()).modelVersion(profile.path("model_version").asText()).metricsJson(output.path("metrics").deepCopy()).evidenceRef(evidence)
                    .capturedAt(captured).recordedAt(Instant.now(clock)).build());row.setCrowdRecordId(id);
        } else if(output.path("metrics").path("intrusion_detected").asBoolean()) {
            detections.saveAndFlush(DetectionEvent.builder().detectionEventId(id).robot(step.getJobTask().getJob().getRobot()).map(zone.getMap()).zone(zone)
                    .eventType(DetectionEventType.RESTRICTED_INTRUSION).locationBasis(DetectionLocationBasis.UNKNOWN).modelVersion(profile.path("model_version").asText())
                    .eventPayloadJson(output.deepCopy()).evidenceRef(evidence).detectedAt(captured).receivedAt(Instant.now(clock)).build());row.setDetectionEventId(id);
        }
        observations.saveAndFlush(row);
    }
    public boolean trustedPersonPosition(DetectionEvent event) {
        JsonNode payload=event.getEventPayloadJson();if(event.getZone()==null || event.getEventType()!=DetectionEventType.OTHER || event.getLocationBasis()!=DetectionLocationBasis.OBJECT
                || event.getXM()==null || event.getYM()==null || !"BE-PERSON-POSITION-1".equals(payload.path("contract").asText())
                || !payload.path("track_id").isTextual() || !payload.path("producer_ref").isTextual() || !payload.path("frame_id").isTextual()
                || !payload.path("track_id").asText().matches("[A-Za-z0-9._:-]{1,128}") || !event.getMap().getFrameId().equals(payload.path("frame_id").asText()))return false;
        var profile=profiles.findByRobotIdAndZoneIdAndSensingType(event.getRobot().getRobotId(),event.getZone().getZoneId(),"CROWD_DENSITY").orElse(null);
        return profile!=null && profile.isEnabled() && profile.isPersonPositionsEnabled() && profile.getModelVersion().equals(event.getModelVersion())
                && profile.getProducerRef().equals(payload.path("producer_ref").asText()) && profile.getMapAssetHash().equals(event.getMap().getAssetHash()) && profile.getFrameId().equals(payload.path("frame_id").asText());
    }
    public JsonNode positionProvenance(DetectionEvent event) {
        if(!trustedPersonPosition(event))fail("Position producer is not commissioned");
        return snapshot(profiles.findByRobotIdAndZoneIdAndSensingType(event.getRobot().getRobotId(),event.getZone().getZoneId(),"CROWD_DENSITY").orElseThrow(),event.getZone());
    }
    public boolean verifiedPersonPosition(DetectionEvent event,JsonNode proof) {
        if(proof==null || !proof.path("person_positions_enabled").asBoolean() || event.getEventType()!=DetectionEventType.OTHER || event.getLocationBasis()!=DetectionLocationBasis.OBJECT
                || event.getXM()==null || event.getYM()==null || event.getZone()==null)return false;
        JsonNode p=event.getEventPayloadJson();
        return "BE-PERSON-POSITION-1".equals(p.path("contract").asText()) && p.path("track_id").isTextual() && p.path("producer_ref").isTextual() && p.path("frame_id").isTextual() && p.path("track_id").asText().matches("[A-Za-z0-9._:-]{1,128}")
                && event.getRobot().getRobotId().toString().equals(proof.path("robot_id").asText()) && event.getZone().getZoneId().toString().equals(proof.path("zone_id").asText())
                && event.getMap().getMapId().toString().equals(proof.path("map_id").asText()) && Objects.equals(event.getMap().getAssetHash(),proof.path("map_asset_hash").asText())
                && Objects.equals(event.getModelVersion(),proof.path("model_version").asText()) && proof.path("producer_ref").asText().equals(p.path("producer_ref").asText())
                && Objects.equals(event.getMap().getFrameId(),proof.path("frame_id").asText()) && proof.path("frame_id").asText().equals(p.path("frame_id").asText());
    }
    private ObjectNode snapshot(AiSensingProfile p,Zone zone) {
        return wire.object().put("profile_id",p.getProfileId().toString()).put("robot_id",p.getRobotId().toString()).put("zone_id",p.getZoneId().toString())
                .put("sensing_type",p.getSensingType()).put("model_version",p.getModelVersion()).put("producer_ref",p.getProducerRef()).put("map_id",zone.getMap().getMapId().toString())
                .put("map_asset_hash",p.getMapAssetHash()).put("frame_id",p.getFrameId()).put("person_positions_enabled",p.isPersonPositionsEnabled());
    }
    private void requireCatalog(Robot robot,Zone zone,String type) {
        if(robot==null || robot.getCurrentMap()==null || !robot.getCurrentMap().getMapId().equals(zone.getMap().getMapId()) || !zone.isActive()
                || robot.getMapAssetHash()==null || !robot.getMapAssetHash().equalsIgnoreCase(zone.getMap().getAssetHash())
                || zone.getMap().getStatus()!=MapStatus.ACTIVE || zone.getMap().getAssetHash()==null || !zone.getMap().getAssetHash().matches("[a-fA-F0-9]{64}")
                || zone.getMap().getFrameId()==null || zone.getMap().getFrameId().isBlank())fail("AI profile requires active zone/map and robot map scope");
        if("RESTRICTED_ZONE".equals(type) && zone.getZoneType()!=ZoneType.KEEP_OUT)fail("RESTRICTED_ZONE requires a KEEP_OUT zone");
    }
    private static void fail(String message){throw new BusinessRuleException(message);}
}
