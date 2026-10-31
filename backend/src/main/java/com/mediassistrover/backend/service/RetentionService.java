package com.mediassistrover.backend.service;

import com.mediassistrover.backend.dto.operations.response.RetentionResponses.Policy;
import com.mediassistrover.backend.dto.operations.response.RetentionResponses.RunResult;

import java.util.List;

public interface RetentionService {
    List<Policy> policies();
    RunResult run(boolean dryRun);
}
