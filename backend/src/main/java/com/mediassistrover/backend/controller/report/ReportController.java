package com.mediassistrover.backend.controller.report;

import com.mediassistrover.backend.dto.report.response.OperationsOverviewResponse;
import com.mediassistrover.backend.dto.report.response.AnalyticsResponses.*;
import com.mediassistrover.backend.dto.common.PageResponse;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.ReportService;
import com.mediassistrover.backend.util.RequestMetadataFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/reports")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Reports", description = "Operational analytics, drill-down and controlled exports")
public class ReportController {
    private final ReportService service;

    @GetMapping("/overview")
    @Operation(summary = "Get job, robot, request, alert and emergency overview")
    public OperationsOverviewResponse overview() { return service.getOperationsOverview(); }

    @GetMapping("/analytics")
    @Operation(summary = "Get time-bucketed operational analytics")
    public AnalyticsReport analytics(@RequestParam Instant from, @RequestParam Instant to,
                                     @RequestParam(defaultValue="UTC") String timezone,
                                     @RequestParam(defaultValue="DAY") ReportInterval interval) {
        return service.getAnalytics(from,to,timezone,interval);
    }

    @GetMapping("/jobs")
    @Operation(summary = "Drill down into jobs used by analytics")
    public PageResponse<JobDetail> jobs(@RequestParam Instant from,@RequestParam Instant to,
                                        @RequestParam(required=false) UUID workflowId,
                                        @RequestParam(required=false) UUID robotId,
                                        @RequestParam(required=false) JobStatus status,
                                        @RequestParam(defaultValue="0") @Min(0) int page,
                                        @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        return PageResponse.from(service.getJobDetails(from,to,workflowId,robotId,status,
                org.springframework.data.domain.PageRequest.of(page,size)));
    }

    @GetMapping(value="/jobs/export.csv",produces="text/csv")
    @Operation(summary = "Export a bounded, audited job report as CSV")
    public ResponseEntity<byte[]> exportJobs(@RequestParam Instant from,@RequestParam Instant to,
                                              @RequestParam(required=false) UUID workflowId,
                                              @RequestParam(required=false) UUID robotId,
                                              @RequestParam(required=false) JobStatus status,
                                              @AuthenticationPrincipal AccountPrincipal actor,
                                              HttpServletRequest request) {
        CsvExport export=service.exportJobs(from,to,workflowId,robotId,status,actor.accountId(),RequestMetadataFactory.from(request));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\""+export.filename()+"\"").contentType(MediaType.parseMediaType("text/csv"))
                .contentLength(export.content().length).body(export.content());
    }
}
