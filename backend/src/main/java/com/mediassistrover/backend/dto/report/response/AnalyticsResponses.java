package com.mediassistrover.backend.dto.report.response;

import com.mediassistrover.backend.enums.*;

import java.time.Instant;
import java.util.*;

public final class AnalyticsResponses {
    private AnalyticsResponses() { }

    public record JobMetrics(long total, long succeeded, long failed, long cancelled, long recoveryRequired,
                             double successRatePercent, Double averageDurationSeconds,
                             Double p50DurationSeconds, Double p95DurationSeconds) { }

    public record RequestMetrics(long total, Map<ServiceRequestType, Long> byType,
                                 Map<ServiceRequestStatus, Long> byStatus) { }

    public record RobotMetrics(long robotCount, long assignedJobs, double utilizationPercent,
                               long offlineEvents, Double currentAverageBatteryPercent) { }

    public record IncidentMetrics(long alerts, long emergencyIncidents, long detectionEvents,
                                  long crowdDensityRecords) { }

    public record DeliveryMetrics(long total, long delivered, long overdue,
                                  Double averageFulfillmentSeconds) { }

    public record EmergencyMetrics(long total, long acknowledged, long resolved,
                                   Double averageAcknowledgementSeconds,
                                   Double averageResolutionSeconds) { }

    public record MonitoringMetrics(long detectionEvents, long suspectedFalls,
                                    long restrictedIntrusions, long sosSignals,
                                    long crowdDensityRecords, Double averagePersonCount,
                                    Long maximumPersonCount) { }

    public record TimeBucket(Instant start, Instant end, String localLabel,
                             long jobs, long succeeded, long failed, long recoveryRequired,
                             long alerts, long emergencyIncidents, long detectionEvents,
                             long crowdDensityRecords) { }

    public record Breakdown(String key, String label, long total, long succeeded, long failed,
                            double successRatePercent) { }

    public record ActivityBreakdown(String key, String label, long detectionEvents,
                                    long crowdDensityRecords, long alerts,
                                    long emergencyIncidents, long observedPersons) { }

    public record AnalyticsReport(Instant generatedAt, Instant from, Instant to, String timezone,
                                  ReportInterval interval, JobMetrics jobs, RequestMetrics requests,
                                  RobotMetrics robots, IncidentMetrics incidents,
                                  DeliveryMetrics deliveries, EmergencyMetrics emergencies,
                                  MonitoringMetrics monitoring,
                                  List<TimeBucket> timeline, List<Breakdown> jobsByWorkflow,
                                  List<Breakdown> jobsByRobot,
                                  List<ActivityBreakdown> activityByMap,
                                  List<ActivityBreakdown> activityByZone) { }

    public record JobDetail(UUID jobId, String jobCode, JobStatus status, String workflowCode,
                            Integer workflowVersion, String robotCode, Instant queuedAt,
                            Instant startedAt, Instant endedAt, Double durationSeconds,
                            String errorCode) { }

    public record CsvExport(byte[] content, String filename, long rowCount) { }
}
