package com.mediassistrover.backend.controller.job;
import com.mediassistrover.backend.mapping.*;
import com.mediassistrover.backend.entity.MappingSession;
import com.mediassistrover.backend.repository.MappingSessionRepository;
import com.mediassistrover.backend.service.JobUiSnapshotService;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.exception.ResourceNotFoundException;
import com.fasterxml.jackson.databind.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/jobs") @RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','STAFF')") @Tag(name="Mapping session")
public class MappingSessionController {
    private final JobUiSnapshotService snapshots;
    private final MappingSessionRepository sessions;
    private final MappingPreviewService previews;
    private final MappingControlService controls;
    private final ObjectMapper json;
    private final com.mediassistrover.backend.repository.RobotMqttSessionRepository links;
    private MappingSession session(UUID jobId,AccountPrincipal actor) {
        snapshots.snapshot(jobId,actor);
        return sessions.findByJobId(jobId).orElseThrow(()->new ResourceNotFoundException("Job has no mapping session"));
    }
    @GetMapping("/{jobId}/mapping-session") public JsonNode detail(@PathVariable UUID jobId,@RequestParam(defaultValue="true") boolean includePreview,@AuthenticationPrincipal AccountPrincipal actor) {
        var job=snapshots.snapshot(jobId,actor).job();
        var s=sessions.findByJobId(jobId).orElseThrow(()->new ResourceNotFoundException("Job has no mapping session"));
        var result=json.createObjectNode().put("mapping_session_id",s.getSessionId().toString()).put("artifact_id",s.getArtifactId().toString())
                .put("state",s.getState()).put("job_status",job.status().name()).put("potentially_open",s.isPotentiallyOpen());
        result.set("preview",includePreview?previews.latest(s.getSessionId()):previews.metadata(s.getSessionId()));
        links.findById(s.getRobotId()).ifPresent(link->{
            result.put("motionObservedAt",link.getLastHeartbeatAt()==null?null:link.getLastHeartbeatAt().toString());
            if(link.getHeartbeatJson()!=null) {
                result.set("motionTelemetry",link.getHeartbeatJson().path("telemetry"));
                var report=link.getHeartbeatJson().path("mappingSession");
                if(s.getSessionId().toString().equals(report.path("session_id").asText()))
                    result.set("autonomy",report.path("autonomy"));
            }
        });
        return result;
    }
    @PostMapping("/{jobId}/mapping-control") @PreAuthorize("hasRole('ADMIN')") @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary="Manual mapping pulse; zero velocities stop. HTTP 202 does not prove robot movement.")
    public MappingControlService.Receipt control(@PathVariable UUID jobId,@Valid @RequestBody MappingControlService.Request body,@AuthenticationPrincipal AccountPrincipal actor) {
        session(jobId,actor);return controls.send(jobId,body);
    }
    @PostMapping("/{jobId}/mapping-autonomy") @PreAuthorize("hasRole('ADMIN')") @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary="Start/renew/stop bounded LiDAR exploration. Renew every 300 ms; HTTP 202 is not Pi acceptance. Does not imply map coverage or save.")
    public MappingControlService.Receipt autonomy(@PathVariable UUID jobId,@Valid @RequestBody MappingControlService.AutonomyRequest body,@AuthenticationPrincipal AccountPrincipal actor) {
        session(jobId,actor);return controls.sendAutonomy(jobId,body);
    }
    @GetMapping("/{jobId}/mapping-control") @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<MappingControlService.Receipt> controlStatus(@PathVariable UUID jobId,@AuthenticationPrincipal AccountPrincipal actor) {
        session(jobId,actor);var receipt=controls.status(jobId);
        return receipt==null?ResponseEntity.noContent().build():ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(receipt);
    }
    @GetMapping(value="/{jobId}/mapping-preview.png",produces=MediaType.IMAGE_PNG_VALUE) @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> image(@PathVariable UUID jobId,@AuthenticationPrincipal AccountPrincipal actor) {
        return picture(previews.mapImage(session(jobId,actor).getSessionId()),MediaType.IMAGE_PNG);
    }
    @GetMapping(value="/{jobId}/camera-preview.jpg",produces=MediaType.IMAGE_JPEG_VALUE) @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> camera(@PathVariable UUID jobId,@AuthenticationPrincipal AccountPrincipal actor) {
        return picture(previews.cameraImage(session(jobId,actor).getSessionId()),MediaType.IMAGE_JPEG);
    }
    private ResponseEntity<byte[]> picture(MappingPreviewService.Picture frame,MediaType type) {
        if(frame==null)return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore())
                .header("X-Frame-Revision",Long.toString(frame.revision())).header("X-Captured-At",frame.captured().toString()).body(frame.bytes());
    }
}
