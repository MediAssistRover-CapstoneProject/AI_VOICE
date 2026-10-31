package com.mediassistrover.backend.service;
import java.time.Instant;
public interface OperationsMaintenanceService {
    int markOverdueJobs(Instant now);
    int markTimedOutSteps(Instant now);
    long cleanupExpiredTokens(Instant now);
}
