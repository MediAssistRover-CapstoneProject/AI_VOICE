package com.mediassistrover.backend.service.impl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.EmergencyIncidentRepository;
import com.mediassistrover.backend.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.*;

@Component @RequiredArgsConstructor
public class NotifyStepHandler implements ServerStepHandler {
    private final AlertService alertService; private final EmergencyIncidentRepository incidentRepository;
    private final ObjectMapper objectMapper; private final Clock clock;
    @Override public boolean supports(JobStep step){return StepType.NOTIFY.name().equals(step.getStepTypeSnapshot());}
    @Override public ServerStepExecution execute(JobStep step,JsonNode inputs){Job job=step.getJobTask().getJob();String type=text(inputs,"notification_type",step.getStepCodeSnapshot());AlertSeverity severity=parse(AlertSeverity.class,text(inputs,"severity","INFO"));String message=text(inputs,"message",type.replace('_',' '));boolean requireAck=inputs.path("require_ack").asBoolean(false);AlertAckPolicy policy=parse(AlertAckPolicy.class,text(inputs,"ack_policy","ANY"));EmergencyIncident incident=null;if(job.getServiceRequest()!=null&&job.getServiceRequest().getRelatedIncidentId()!=null)incident=incidentRepository.findById(job.getServiceRequest().getRelatedIncidentId()).orElse(null);Alert alert=alertService.createSystemAlert("NOTIFY:"+step.getJobStepId(),type,severity,message,job.getSchemaVersion()==3?inputs.path("data"):inputs,incident,job.getServiceRequest(),step,null,requireAck,policy,job.getSchemaVersion()==3?job.getStartedAt().plusSeconds(job.getWorkflow().getMaxDurationSec()):Instant.now(clock).plusSeconds(step.getTimeoutSecSnapshot()),inputs.path("recipients"));if(job.getSchemaVersion()==3){ObjectNode output=objectMapper.createObjectNode().put("alert_id",alert.getAlertId().toString()).put("notification_type",alert.getNotificationType()).put("persisted",true).put("requires_ack",alert.isRequireAck());return new ServerStepExecution(output,true);}ObjectNode result=objectMapper.createObjectNode().put("notification_id",alert.getAlertId().toString()).put("delivered",false).put("acked",false);return new ServerStepExecution(result,false);}
    private String text(JsonNode node,String field,String fallback){return node!=null&&node.path(field).isTextual()?node.path(field).asText():fallback;}
    private <E extends Enum<E>> E parse(Class<E> type,String value){try{return Enum.valueOf(type,value);}catch(Exception ex){throw new com.mediassistrover.backend.exception.BusinessRuleException("Invalid NOTIFY "+type.getSimpleName()+": "+value);}}
}
