package com.mediassistrover.backend.controller.job;

import com.mediassistrover.backend.service.JobExecutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/speech-jobs")
@PreAuthorize("hasRole('ADMIN')") @RequiredArgsConstructor
@Tag(name="Robot Speech", description="Run a published INTERACT/SPEAK workflow through MQTT")
public class SpeechJobController {
    private final JobExecutionService execution;
    @Schema(name="SpeechJobCreateRequest") public record Create(@NotNull UUID workflowId, @NotNull UUID robotId,
                         @NotBlank @Size(max=500) String text,
                         @NotNull @Pattern(regexp="vi|en") String languageCode) { }
    public record Created(UUID jobId, String jobCode) { }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary="Send text to the robot speaker as a workflow Job")
    public Created create(@Valid @RequestBody Create input) {
        var job=execution.createForSpeech(input.workflowId(),input.robotId(),input.text(),input.languageCode());
        return new Created(job.getJobId(),job.getJobCode());
    }
}
