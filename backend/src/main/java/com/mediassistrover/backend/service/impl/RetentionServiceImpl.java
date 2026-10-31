package com.mediassistrover.backend.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.dto.operations.response.RetentionResponses.*;
import com.mediassistrover.backend.entity.AuditLog;
import com.mediassistrover.backend.enums.RetentionMode;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.repository.AuditLogRepository;
import com.mediassistrover.backend.service.RetentionService;
import com.mediassistrover.backend.service.RuntimeSettingService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class RetentionServiceImpl implements RetentionService {
    private static final List<DatasetSpec> DATASETS = List.of(
            new DatasetSpec("alert_recipient", "AlertRecipient", "alert_recipient_id", "t.created_at",
                    "retention.alert_days", "retention.alert_mode", 1095,
                    "JOIN `Alert` parent ON parent.alert_id=t.alert_id", "parent.status='CLOSED'"),
            new DatasetSpec("alert", "Alert", "alert_id", "t.created_at",
                    "retention.alert_days", "retention.alert_mode", 1095, "",
                    "t.status='CLOSED' AND NOT EXISTS (SELECT 1 FROM `AlertRecipient` ar WHERE ar.alert_id=t.alert_id)"),
            new DatasetSpec("emergency_incident", "EmergencyIncident", "incident_id", "t.closed_at",
                    "retention.emergency_incident_days", "retention.emergency_incident_mode", 1095, "",
                    "t.status='CLOSED' AND t.closed_at IS NOT NULL AND NOT EXISTS (SELECT 1 FROM `Alert` a WHERE a.incident_id=t.incident_id)"),
            new DatasetSpec("detection_event", "DetectionEvent", "detection_event_id", "t.detected_at",
                    "retention.detection_event_days", "retention.detection_event_mode", 365, "",
                    "NOT EXISTS (SELECT 1 FROM `Alert` a WHERE a.detection_event_id=t.detection_event_id) " +
                            "AND NOT EXISTS (SELECT 1 FROM `EmergencyIncident` e WHERE e.detection_event_id=t.detection_event_id)"),
            new DatasetSpec("job_event", "JobEvent", "job_event_id", "t.persisted_at",
                    "retention.job_event_days", "retention.job_event_mode", 1095, "", "1=1"),
            new DatasetSpec("robot_status_history", "RobotStatusHistory", "robot_status_history_id", "t.recorded_at",
                    "retention.robot_status_history_days", "retention.robot_status_history_mode", 365, "", "1=1"),
            new DatasetSpec("robot_location_history", "RobotLocationHistory", "robot_location_history_id", "t.captured_at",
                    "retention.robot_location_history_days", "retention.robot_location_history_mode", 183, "", "1=1"),
            new DatasetSpec("crowd_density_record", "CrowdDensityRecord", "crowd_density_record_id", "t.captured_at",
                    "retention.crowd_density_record_days", "retention.crowd_density_record_mode", 365, "", "1=1"),
            new DatasetSpec("heatmap_record", "HeatmapRecord", "heatmap_record_id", "t.generated_at",
                    "retention.heatmap_record_days", "retention.heatmap_record_mode", 365, "", "1=1"),
            new DatasetSpec("audit_log", "AuditLog", "audit_log_id", "t.occurred_at",
                    "retention.audit_log_days", "retention.audit_log_mode", 1095, "", "1=1")
    );

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final RuntimeSettingService settings;
    private final AuditLogRepository auditRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Value("${app.operations.retention-enabled:false}") private boolean enabledFallback;
    @Value("${app.operations.retention-batch-size:200}") private int batchSizeFallback;
    @Value("${app.operations.retention-max-batches:10}") private int maxBatchesFallback;

    @Override
    public List<Policy> policies() {
        String defaultMode = settings.getString("retention.default_mode", RetentionMode.DISABLED.name());
        return DATASETS.stream().map(spec -> new Policy(spec.dataset(), days(spec), mode(spec, defaultMode))).toList();
    }

    @Override
    public RunResult run(boolean dryRun) {
        Instant startedAt = Instant.now(clock);
        boolean executionEnabled = settings.getBoolean("retention.execution_enabled", enabledFallback);
        if (!dryRun && !executionEnabled) {
            throw new BusinessRuleException("Retention execution is disabled; use dry-run or enable retention.execution_enabled");
        }
        int batchSize = (int) settings.getLong("retention.batch_size", batchSizeFallback, 1, 5000);
        int maxBatches = (int) settings.getLong("retention.max_batches_per_run", maxBatchesFallback, 1, 1000);
        String defaultMode = settings.getString("retention.default_mode", RetentionMode.DISABLED.name());
        List<DatasetResult> results = new ArrayList<>();

        Timer.Sample sample = Timer.start(meterRegistry);
        for (DatasetSpec spec : DATASETS) {
            results.add(process(spec, mode(spec, defaultMode), days(spec), dryRun, batchSize, maxBatches));
        }
        Instant completedAt = Instant.now(clock);
        sample.stop(meterRegistry.timer("mediassist.retention.run.duration", "dryRun", Boolean.toString(dryRun)));
        auditRepository.save(AuditLog.builder().actionCode(dryRun ? "RETENTION_DRY_RUN" : "RETENTION_EXECUTED")
                .entityType("RetentionPolicy").afterData(auditSummary(results, dryRun, batchSize, maxBatches))
                .correlationId("retention-" + UUID.randomUUID()).occurredAt(completedAt).build());
        return new RunResult(startedAt, completedAt, dryRun, executionEnabled, batchSize, maxBatches,
                List.copyOf(results));
    }

    private DatasetResult process(DatasetSpec spec, RetentionMode mode, int days, boolean dryRun,
                                  int batchSize, int maxBatches) {
        Instant cutoff = Instant.now(clock).minus(days, java.time.temporal.ChronoUnit.DAYS);
        long eligible = count(spec, cutoff);
        if (mode == RetentionMode.DISABLED || dryRun || eligible == 0) {
            metric(spec, mode, "eligible", eligible);
            return new DatasetResult(spec.dataset(), mode, cutoff, eligible, 0, 0, 0, false, null);
        }

        long processed = 0;
        long archived = 0;
        long deleted = 0;
        String error = null;
        try {
            for (int batch = 0; batch < maxBatches; batch++) {
                BatchResult result = transactionTemplate.execute(status -> processBatch(spec, mode, cutoff, batchSize));
                if (result == null || result.processed() == 0) break;
                processed += result.processed();
                archived += result.archived();
                deleted += result.deleted();
                if (result.processed() < batchSize) break;
            }
        } catch (RuntimeException exception) {
            error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            meterRegistry.counter("mediassist.retention.failures", "dataset", spec.dataset()).increment();
        }
        boolean truncated = error == null && count(spec, cutoff) > 0;
        metric(spec, mode, "processed", processed);
        metric(spec, mode, "archived", archived);
        metric(spec, mode, "deleted", deleted);
        return new DatasetResult(spec.dataset(), mode, cutoff, eligible, processed, archived, deleted,
                truncated, error);
    }

    private BatchResult processBatch(DatasetSpec spec, RetentionMode mode, Instant cutoff, int batchSize) {
        String select = "SELECT t.* FROM `" + spec.table() + "` t " + spec.joinClause() +
                " WHERE " + spec.timestampExpression() + " < ? AND " + spec.predicate() +
                " ORDER BY " + spec.timestampExpression() + " ASC LIMIT ?";
        List<Map<String, Object>> rows = jdbc.queryForList(select, Timestamp.from(cutoff), batchSize);
        if (rows.isEmpty()) return new BatchResult(0, 0, 0);

        long archived = 0;
        if (mode == RetentionMode.ARCHIVE) {
            for (Map<String, Object> row : rows) {
                byte[] sourceId = binaryId(value(row, spec.primaryKey()));
                Instant sourceTimestamp = timestamp(value(row, columnName(spec.timestampExpression())));
                String payload = json(row);
                jdbc.update("INSERT IGNORE INTO `RetentionArchive` " +
                                "(retention_archive_id,dataset_name,source_table,source_record_id,source_timestamp,payload_json,archived_at,archive_version) " +
                                "VALUES (?,?,?,?,?,CAST(? AS JSON),?,1)",
                        uuidBytes(UUID.randomUUID()), spec.dataset(), spec.table(), sourceId,
                        Timestamp.from(sourceTimestamp), payload, Timestamp.from(Instant.now(clock)));
                Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM `RetentionArchive` WHERE dataset_name=? AND source_record_id=?",
                        Integer.class, spec.dataset(), sourceId);
                if (exists == null || exists != 1) throw new IllegalStateException("Archive verification failed for " + spec.dataset());
                archived++;
            }
        }

        List<byte[]> ids = rows.stream().map(row -> binaryId(value(row, spec.primaryKey()))).toList();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        int deleted = jdbc.update("DELETE FROM `" + spec.table() + "` WHERE `" + spec.primaryKey() + "` IN (" + placeholders + ")",
                ids.toArray());
        if (deleted != rows.size()) throw new IllegalStateException("Retention delete count mismatch for " + spec.dataset());
        return new BatchResult(rows.size(), archived, deleted);
    }

    private long count(DatasetSpec spec, Instant cutoff) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM `" + spec.table() + "` t " + spec.joinClause() +
                        " WHERE " + spec.timestampExpression() + " < ? AND " + spec.predicate(),
                Long.class, Timestamp.from(cutoff));
        return count == null ? 0 : count;
    }

    private int days(DatasetSpec spec) {
        return (int) settings.getLong(spec.daysKey(), spec.defaultDays(), 1, 36500);
    }

    private RetentionMode mode(DatasetSpec spec, String defaultMode) {
        String value = settings.getString(spec.modeKey(), defaultMode);
        try {
            return RetentionMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return RetentionMode.DISABLED;
        }
    }

    private void metric(DatasetSpec spec, RetentionMode mode, String outcome, long value) {
        meterRegistry.counter("mediassist.retention.records", "dataset", spec.dataset(),
                "mode", mode.name(), "outcome", outcome).increment(value);
    }

    private Map<String, Object> auditSummary(List<DatasetResult> results, boolean dryRun, int batchSize, int maxBatches) {
        long eligible = results.stream().mapToLong(DatasetResult::eligible).sum();
        long archived = results.stream().mapToLong(DatasetResult::archived).sum();
        long deleted = results.stream().mapToLong(DatasetResult::deleted).sum();
        long failures = results.stream().filter(result -> result.error() != null).count();
        return Map.of("dryRun", dryRun, "batchSize", batchSize, "maxBatches", maxBatches,
                "eligible", eligible, "archived", archived, "deleted", deleted, "failures", failures);
    }

    private String json(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        row.forEach((key, value) -> normalized.put(key, normalize(value, key)));
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize retention archive payload", exception);
        }
    }

    private Object normalize(Object value, String column) {
        if (value instanceof byte[] bytes) return bytes.length == 16 ? uuid(bytes).toString() : Base64.getEncoder().encodeToString(bytes);
        if (value instanceof Timestamp timestamp) return timestamp.toInstant().toString();
        if (value instanceof LocalDateTime dateTime) return dateTime.toInstant(ZoneOffset.UTC).toString();
        if (value instanceof java.sql.Date date) return date.toLocalDate().toString();
        if (value instanceof String text && column.toLowerCase(Locale.ROOT).endsWith("_json")) {
            try { return objectMapper.readTree(text); } catch (JsonProcessingException ignored) { return text; }
        }
        return value;
    }

    private Object value(Map<String, Object> row, String column) {
        return row.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(column))
                .map(Map.Entry::getValue).findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing retention column " + column));
    }

    private byte[] binaryId(Object value) {
        if (value instanceof byte[] bytes && bytes.length == 16) return bytes;
        throw new IllegalStateException("Retention source key is not BINARY(16)");
    }

    private Instant timestamp(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof LocalDateTime dateTime) return dateTime.toInstant(ZoneOffset.UTC);
        if (value instanceof java.util.Date date) return date.toInstant();
        throw new IllegalStateException("Retention timestamp is invalid");
    }

    private String columnName(String expression) { return expression.substring(expression.indexOf('.') + 1); }
    private byte[] uuidBytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    private UUID uuid(byte[] bytes) { ByteBuffer buffer=ByteBuffer.wrap(bytes); return new UUID(buffer.getLong(),buffer.getLong()); }

    private record DatasetSpec(String dataset, String table, String primaryKey, String timestampExpression,
                               String daysKey, String modeKey, int defaultDays,
                               String joinClause, String predicate) { }
    private record BatchResult(long processed, long archived, long deleted) { }
}
