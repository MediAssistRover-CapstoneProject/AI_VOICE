package com.mediassistrover.backend.mqtt;

import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.JobEventRepository;
import com.mediassistrover.backend.service.OrchestrationWorkService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Called with the Job lock held. No recovery path releases a potentially moving robot. */
@Service @RequiredArgsConstructor
public class MqttJobSafety {
    private final JobEventRepository events;
    private final OrchestrationWorkService wakeups;
    private final MqttWire wire;
    private final Clock clock;
    private final org.springframework.context.ApplicationEventPublisher updates;
    public void recovery(Job job,String reason) {
        if (!job.isBeOrchestrated() || Set.of(JobStatus.SUCCEEDED,JobStatus.FAILED,JobStatus.CANCELLED).contains(job.getStatus())) return;
        boolean changed=job.getStatus()!=JobStatus.RECOVERY_REQUIRED;
        job.setStatus(JobStatus.RECOVERY_REQUIRED);job.setErrorCode("MQTT_RECOVERY_REQUIRED");job.setFailureReason(reason);
        if (job.getRobot()!=null) job.getRobot().setStatus(RobotStatus.ERROR);
        if (job.getServiceRequest()!=null) job.getServiceRequest().setStatus(ServiceRequestStatus.RECOVERY_REQUIRED);
        if (changed) {
            Instant now=Instant.now(clock);
            events.save(JobEvent.builder().jobEventId(UUID.randomUUID()).job(job).robot(job.getRobot()).producerType(JobEventProducerType.SERVER)
                    .producerSessionId(UUID.randomUUID()).sequenceNo(1).eventType("MQTT_RECOVERY_REQUIRED").statusScope("JOB")
                    .newStatus(JobStatus.RECOVERY_REQUIRED.name()).eventDataJson(wire.object().put("reason",reason)).occurredAt(now).receivedAt(now).persistedAt(now).build());
        }
        wakeups.request(job);
        updates.publishEvent(new com.mediassistrover.backend.service.RuntimeChanged(job.getJobId()));
    }
}
