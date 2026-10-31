package com.mediassistrover.backend.service;

public interface JobDispatchService {
    int dispatchQueuedJobs(int limit);
}
