package com.mediassistrover.backend.dto.operations.response;

import com.mediassistrover.backend.enums.RetentionMode;

import java.time.Instant;
import java.util.List;

public final class RetentionResponses {
    private RetentionResponses() { }

    public record Policy(String dataset, int retentionDays, RetentionMode mode) { }

    public record DatasetResult(String dataset, RetentionMode mode, Instant cutoff,
                                long eligible, long processed, long archived, long deleted,
                                boolean truncated, String error) { }

    public record RunResult(Instant startedAt, Instant completedAt, boolean dryRun,
                            boolean executionEnabled, int batchSize, int maxBatches,
                            List<DatasetResult> datasets) { }
}
