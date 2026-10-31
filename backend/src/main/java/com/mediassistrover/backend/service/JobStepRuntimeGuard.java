package com.mediassistrover.backend.service;

import com.mediassistrover.backend.entity.Job;
import com.mediassistrover.backend.entity.JobStep;

public interface JobStepRuntimeGuard {
    void requireStartAllowed(JobStep candidate);
    void requireSuccessfulTerminal(Job job);
}
