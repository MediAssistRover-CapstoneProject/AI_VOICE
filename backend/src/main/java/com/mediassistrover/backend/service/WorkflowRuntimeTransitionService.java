package com.mediassistrover.backend.service;

import com.mediassistrover.backend.entity.JobStep;
import com.mediassistrover.backend.entity.StepTransition;

public interface WorkflowRuntimeTransitionService {
    StepTransition resolve(JobStep completedStep);
}
