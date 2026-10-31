package com.mediassistrover.backend.service;
import com.mediassistrover.backend.entity.JobStep;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.exception.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;
@Component @RequiredArgsConstructor
public class StaffIncidentReviewPolicy {
    private final JobStepRepository steps;
    private final JobStepResponseRepository responses;
    public void requireConfirmed(JobStep target,UUID detectionId) {
        var job=target.getJobTask().getJob();if(!job.isBeOrchestrated() || job.getSchemaVersion()!=3)return;
        boolean reviewed=steps.findByJobId(job.getJobId()).stream().filter(s->s.getStatus()==JobStepStatus.SUCCEEDED && "INTERACT".equals(s.getStepTypeSnapshot())
                && s.getResultJson()!=null && s.getResultJson().path("confirmed").asBoolean() && "STAFF_MANUAL".equals(s.getResultJson().path("source").asText())
                && s.getResolvedInputsJson().path("data").path("detection_event_id").asText().equals(detectionId.toString()))
                .anyMatch(s->responses.findByJobStepJobStepId(s.getJobStepId()).stream().anyMatch(r->r.getResultJson().equals(s.getResultJson())
                        && Set.of(Role.ADMIN,Role.STAFF).contains(r.getActorAccount().getRole()) && r.getResultJson().path("actor_account_id").asText().equals(r.getActorAccount().getAccountId().toString())));
        if(!reviewed)throw new BusinessRuleException("CREATE_FALL_INCIDENT requires a committed positive staff review for this detection/Job");
    }
}
