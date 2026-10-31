package com.mediassistrover.backend.controller.operations;

import com.mediassistrover.backend.dto.operations.response.RetentionResponses.Policy;
import com.mediassistrover.backend.dto.operations.response.RetentionResponses.RunResult;
import com.mediassistrover.backend.service.RetentionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/operations/retention")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name="Admin Retention Operations")
public class AdminRetentionController {
    private final RetentionService retentionService;

    @GetMapping("/policies")
    @Operation(summary="View effective retention policy")
    public List<Policy> policies() { return retentionService.policies(); }

    @PostMapping("/runs")
    @Operation(summary="Run retention; dry-run is the safe default")
    public RunResult run(@RequestParam(defaultValue="true") boolean dryRun) {
        return retentionService.run(dryRun);
    }
}
