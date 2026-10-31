package com.mediassistrover.backend.mapping;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.DevicePrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import java.io.InputStream;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class MappingArtifactService {
    private final MappingSessionRepository sessions;
    private final MapArtifactRepository artifacts;
    private final JobRepository jobs;
    private final RobotRepository robots;
    private final JobStepRepository steps;
    private final FloorRepository floors;
    private final FacilityMapRepository maps;
    private final MappingArchiveReader reader;
    private final MappingArtifactStorage storage;
    private final Clock clock;
    private final ObjectMapper json;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;
    public record Receipt(boolean stored,UUID artifactId,String assetHash) { }

    @Transactional
    public Receipt upload(UUID sessionId,UUID artifactId,String assetHash,String archiveHash,InputStream input,DevicePrincipal principal) {
        MappingSession initial=sessions.findById(sessionId).orElseThrow(()->new ResourceNotFoundException("Mapping session not found"));
        Job job=jobs.lockById(initial.getJobId()).orElseThrow();Robot robot=robots.findByIdForUpdate(initial.getRobotId()).orElseThrow();requireDevice(robot,principal);
        MappingSession session=sessions.findById(sessionId).orElseThrow();
        em.refresh(session,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if(!session.getArtifactId().equals(artifactId))throw new AccessDeniedException("Artifact ID was not issued for this session");
        MappingArchiveReader.Verified verified=reader.verify(input,artifactId,assetHash,archiveHash);
        MapArtifact prior=artifacts.lock(artifactId).orElse(null);
        if(prior!=null) {
            if(!prior.getArchiveHash().equals(verified.archiveHash()) || !prior.getAssetHash().equals(verified.assetHash()))throw new ConflictException("Artifact ID reused with different content");
            storage.read(artifactId,prior.getArchiveHash());return new Receipt(true,artifactId,prior.getAssetHash());
        }
        JobStep selected=job.getSelectedJobStep();
        if(job.getStatus()!=JobStatus.RUNNING || job.getCancelRequestedAt()!=null || !session.isPotentiallyOpen()
                || !MappingLifecycleService.mapping(selected) || !"SAVE".equals(selected.getResolvedInputsJson().path("action").asText())
                || !Set.of(JobStepStatus.RUNNING,JobStepStatus.SUCCEEDED).contains(selected.getStatus())
                || !selected.getResolvedInputsJson().path("artifact_id").asText().equals(artifactId.toString()))throw new BusinessRuleException("Upload requires this Job's active SAVE operation");
        // First upload cannot manufacture an artifact for an unissued/unpublished SAVE.
        if(!"SAVING".equals(session.getState()) && !"SAVED_LOCAL".equals(session.getState()) && !"SAVED".equals(session.getState()))throw new BusinessRuleException("SAVE has not been issued");
        if(selected.getStatus()==JobStepStatus.SUCCEEDED) verified.manifest().fields().forEachRemaining(e->{JsonNode v=selected.getResultJson().path("metrics").path(e.getKey());
            if(!(e.getValue().isNumber() && v.isNumber()?e.getValue().decimalValue().compareTo(v.decimalValue())==0:e.getValue().equals(v)))throw new BusinessRuleException("Uploaded manifest differs from SAVE result");});
        storage.store(artifactId,verified);
        artifacts.saveAndFlush(MapArtifact.builder().artifactId(artifactId).sessionId(sessionId).archiveHash(verified.archiveHash()).assetHash(verified.assetHash())
                .archiveSize(verified.archive().length).manifestJson(verified.manifest()).storedAt(Instant.now(clock)).build());
        return new Receipt(true,artifactId,verified.assetHash());
    }
    /** Invoked by the selected server handler in its existing orchestration transaction. */
    public ObjectNode commit(JobStep step,JsonNode input) {
        Job job=step.getJobTask().getJob();
        MappingSession session=sessions.findByJobId(job.getJobId()).orElseThrow(()->new BusinessRuleException("No BE mapping session"));
        if(!job.isBeOrchestrated() || job.getSchemaVersion()!=3 || job.getStatus()!=JobStatus.RUNNING || job.getSelectedJobStep()==null
                || !job.getSelectedJobStep().getJobStepId().equals(step.getJobStepId()) || !session.getSessionId().toString().equals(input.path("mapping_session_id").asText())
                || !session.getArtifactId().toString().equals(input.path("artifact_id").asText()))throw new BusinessRuleException("COMMIT_MAP_ARTIFACT is outside selected Job/session scope");
        MapArtifact artifact=artifacts.findById(session.getArtifactId()).orElseThrow(()->new BusinessRuleException("Artifact has no durable upload receipt"));
        boolean saved=steps.findByJobId(job.getJobId()).stream().anyMatch(s->s.getStatus()==JobStepStatus.SUCCEEDED && MappingLifecycleService.mapping(s)
                && "SAVE".equals(s.getResolvedInputsJson().path("action").asText()) && s.getResultJson().path("metrics").path("artifact_id").asText().equals(session.getArtifactId().toString()));
        if(!saved)throw new BusinessRuleException("COMMIT requires completed SAVE for this artifact");
        UUID floorId=UUID.fromString(input.path("floor_id").asText());Floor floor=floors.findById(floorId).orElseThrow(()->new BusinessRuleException("Floor not found"));
        if(job.getInputJson().has("floor_id") && !floorId.toString().equalsIgnoreCase(job.getInputJson().path("floor_id").asText()))throw new BusinessRuleException("Artifact floor differs from mapping Job input");
        if(!floor.isActive())throw new BusinessRuleException("Mapping requires an active floor");
        if(job.getMap()!=null && !job.getMap().getFloor().getFloorId().equals(floorId))throw new BusinessRuleException("Artifact floor differs from Job floor");
        storage.read(artifact.getArtifactId(),artifact.getArchiveHash());boolean created=artifact.getMapId()==null;FacilityMap map;
        if(!created) {
            map=maps.findById(artifact.getMapId()).orElseThrow();
            if(!map.getFloor().getFloorId().equals(floorId) || !map.getMapName().equals(input.path("map_name").asText()))throw new ConflictException("Committed artifact metadata cannot change");
        } else {
            JsonNode n=artifact.getManifestJson();
            map=maps.saveAndFlush(FacilityMap.builder().floor(floor).mapCode("MAP_"+artifact.getArtifactId().toString().replace("-","")).versionNo(1)
                    .mapName(input.path("map_name").asText()).status(MapStatus.DRAFT).widthM(n.path("width_m").decimalValue()).heightM(n.path("height_m").decimalValue())
                    .resolutionMPerPixel(n.path("resolution_m_per_pixel").decimalValue()).originXM(n.path("origin_x_m").decimalValue()).originYM(n.path("origin_y_m").decimalValue())
                    .originYawRad(n.path("origin_yaw_rad").decimalValue()).frameId(n.path("frame_id").asText()).assetHash(artifact.getAssetHash())
                    .mapFileUri("/api/v1/pi/mapping-artifacts/"+artifact.getArtifactId()).build());artifact.setMapId(map.getMapId());
        }
        return json.createObjectNode().put("map_id",map.getMapId().toString()).put("artifact_id",artifact.getArtifactId().toString()).put("asset_hash",artifact.getAssetHash()).put("created",created);
    }
    @Transactional(readOnly=true)
    public byte[] download(UUID artifactId,DevicePrincipal principal) {
        MapArtifact artifact=artifacts.findById(artifactId).orElseThrow(()->new ResourceNotFoundException("Artifact not found"));
        MappingSession session=sessions.findById(artifact.getSessionId()).orElseThrow();Robot robot=robots.findById(principal.robotId()).orElseThrow();requireDevice(robot,principal);
        if(!session.getRobotId().equals(robot.getRobotId()) && (artifact.getMapId()==null || robot.getCurrentMap()==null || !artifact.getMapId().equals(robot.getCurrentMap().getMapId())))throw new AccessDeniedException("Artifact is outside robot scope");
        return storage.read(artifactId,artifact.getArchiveHash());
    }
    private void requireDevice(Robot robot,DevicePrincipal principal) {
        if(principal==null || !robot.getRobotId().equals(principal.robotId()) || !robot.getRobotCode().equals(principal.robotCode())
                || robot.getDeviceTokenVersion()!=principal.tokenVersion() || principal.expiresAt()==null || !principal.expiresAt().isAfter(Instant.now(clock)))throw new AccessDeniedException("Current device session required");
    }
}
