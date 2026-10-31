package com.mediassistrover.backend.controller.job;
import com.mediassistrover.backend.service.JobExecutionService;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController @RequestMapping("/api/v1/mapping-jobs") @PreAuthorize("hasRole('ADMIN')") @RequiredArgsConstructor
public class MappingJobController {
    private final JobExecutionService execution;
    @Schema(name="MappingJobCreateRequest") public record Create(@NotNull UUID workflowId,@NotNull UUID robotId,@NotNull UUID floorId,@NotBlank @Size(max=160) String mapName) { }
    public record Created(UUID jobId,String jobCode) { }
    @PostMapping public Created create(@Valid @RequestBody Create input) {var job=execution.createForMapping(input.workflowId(),input.robotId(),input.floorId(),input.mapName());return new Created(job.getJobId(),job.getJobCode());}
}
