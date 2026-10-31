package com.mediassistrover.backend.service;

import com.mediassistrover.backend.dto.report.response.OperationsOverviewResponse;
import com.mediassistrover.backend.dto.report.response.AnalyticsResponses.*;
import com.mediassistrover.backend.enums.*;
import org.springframework.data.domain.*;

import java.time.Instant;
import java.util.UUID;

public interface ReportService {
    OperationsOverviewResponse getOperationsOverview();
    AnalyticsReport getAnalytics(Instant from, Instant to, String timezone, ReportInterval interval);
    Page<JobDetail> getJobDetails(Instant from, Instant to, UUID workflowId, UUID robotId,
                                  JobStatus status, Pageable pageable);
    CsvExport exportJobs(Instant from, Instant to, UUID workflowId, UUID robotId, JobStatus status,
                         UUID actorId, RequestMetadata metadata);
}
