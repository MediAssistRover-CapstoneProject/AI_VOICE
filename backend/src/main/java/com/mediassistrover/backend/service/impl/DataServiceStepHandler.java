package com.mediassistrover.backend.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.Endpoint;
import com.mediassistrover.backend.entity.DetectionEvent;
import com.mediassistrover.backend.entity.EmergencyIncident;
import com.mediassistrover.backend.entity.JobStep;
import com.mediassistrover.backend.enums.DetectionEventType;
import com.mediassistrover.backend.enums.EmergencyIncidentSeverity;
import com.mediassistrover.backend.enums.EmergencyIncidentStatus;
import com.mediassistrover.backend.enums.StepType;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.repository.EndpointGroupMemberRepository;
import com.mediassistrover.backend.repository.EndpointRepository;
import com.mediassistrover.backend.repository.DetectionEventRepository;
import com.mediassistrover.backend.repository.EmergencyIncidentRepository;
import com.mediassistrover.backend.service.ServerStepHandler;
import com.mediassistrover.backend.service.HospitalInformationQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class DataServiceStepHandler implements ServerStepHandler {
    public static final Set<String> SUPPORTED_SERVICE_KEYS=Set.of(
            "QUERY_HOSPITAL_INFORMATION","RESOLVE_TARGETS","SELECT_TARGET","CHECK_TARGET","CREATE_FALL_INCIDENT","COMMIT_MAP_ARTIFACT");
    private final HospitalInformationQueryService informationQueryService;
    private final EndpointRepository endpointRepository;
    private final EndpointGroupMemberRepository groupMemberRepository;
    private final DetectionEventRepository detectionEventRepository;
    private final EmergencyIncidentRepository incidentRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final com.mediassistrover.backend.service.NavigationTargetService navigation;
    private final com.mediassistrover.backend.service.StaffIncidentReviewPolicy incidentReview;

    @Override public boolean supports(JobStep step){return step.getStep().getStepType()==StepType.DATA_SERVICE
            && !"COMMIT_MAP_ARTIFACT".equals(step.getResolvedInputsJson().path("service_key").asText());}

    @Override public ServerStepExecution execute(JobStep step,JsonNode inputs){
        String key=inputs.path("service_key").asText(step.getStepCodeSnapshot().equals("QUERY")?"QUERY_HOSPITAL_INFORMATION":step.getStepCodeSnapshot());
        ObjectNode result=objectMapper.createObjectNode().put("request_id",step.getOperationKey());ObjectNode data=result.putObject("data");
        switch(key){
            case "QUERY_HOSPITAL_INFORMATION" -> {
                String question=inputs.path("question").asText();
                String language=inputs.path("language_code").asText("vi-VN");
                var answer=informationQueryService.query(question,language);
                data.put("found",answer.found()).put("answer",answer.answer()).put("language_code",language);
                ArrayNode items=data.putArray("items");
                if(answer.source()!=null){
                    ObjectNode source=data.putObject("source")
                            .put("article_id",answer.source().articleId().toString())
                            .put("article_code",answer.source().articleCode())
                            .put("category",answer.source().category())
                            .put("title",answer.source().title())
                            .put("language_code",answer.source().languageCode())
                            .put("updated_at",answer.source().updatedAt().toString());
                    items.addObject().put("id",answer.source().articleId().toString())
                            .put("category",answer.source().category()).put("title",answer.source().title())
                            .put("content",answer.answer()).put("languageCode",answer.source().languageCode());
                    data.set("source",source);
                }else{
                    data.putNull("source");
                    data.put("fallback_code",answer.fallbackCode());
                }
                data.put("count",items.size());
            }
            case "RESOLVE_TARGETS" -> {
                if(step.getJobTask().getJob().isBeOrchestrated() && step.getJobTask().getJob().getSchemaVersion()==3) {
                    data.set("targets",navigation.resolve(step,uuid(inputs,"endpoint_group_id")));break;
                }
                UUID groupId=uuid(inputs,"endpoint_group_id");ArrayNode targets=data.putArray("targets");
                groupMemberRepository.findByEndpointGroupEndpointGroupIdOrderBySequenceNo(groupId).stream().filter(m->m.isActive()&&m.getEndpoint().isActive()).forEach(m->targets.add(endpoint(m.getEndpoint())));
            }
            case "SELECT_TARGET" -> {
                if(step.getJobTask().getJob().isBeOrchestrated() && step.getJobTask().getJob().getSchemaVersion()==3) {
                    data.setAll(navigation.select(step,inputs.path("targets"),inputs.path("index").asInt(0)));break;
                }
                JsonNode targets=inputs.path("targets");int index=inputs.path("index").asInt(0);boolean valid=targets.isArray()&&index>=0&&index<targets.size();data.put("has_target",valid);if(valid)data.set("target",targets.get(index).deepCopy());if(valid&&targets.get(index).has("endpoint_id"))data.set("endpoint_id",targets.get(index).get("endpoint_id"));
            }
            case "CHECK_TARGET" -> {
                if(step.getJobTask().getJob().isBeOrchestrated() && step.getJobTask().getJob().getSchemaVersion()==3) {
                    data.setAll(navigation.check(step,uuid(inputs,"endpoint_id")));break;
                }
                UUID endpointId=uuid(inputs,"endpoint_id");Endpoint endpoint=endpointRepository.findById(endpointId).orElse(null);boolean valid=endpoint!=null&&endpoint.isActive()&&endpoint.isAccessible();data.put("is_valid",valid);if(valid)data.set("endpoint",endpoint(endpoint));
            }
            case "CREATE_FALL_INCIDENT" -> {
                UUID detectionId=uuid(inputs,"detection_event_id");
                incidentReview.requireConfirmed(step,detectionId);
                DetectionEvent detection=detectionEventRepository.findById(detectionId)
                        .orElseThrow(()->new BusinessRuleException("DetectionEvent not found: "+detectionId));
                if(detection.getEventType()!=DetectionEventType.SUSPECTED_FALL)
                    throw new BusinessRuleException("CREATE_FALL_INCIDENT requires a SUSPECTED_FALL detection");
                if(step.getJobTask().getJob().getRobot()!=null&&!step.getJobTask().getJob().getRobot().getRobotId().equals(detection.getRobot().getRobotId()))
                    throw new BusinessRuleException("DetectionEvent belongs to another Robot");
                EmergencyIncident existing=incidentRepository.findByDetectionEventDetectionEventId(detectionId).orElse(null);
                EmergencyIncident incident=existing!=null?existing:
                        incidentRepository.saveAndFlush(EmergencyIncident.builder().detectionEvent(detection)
                                .robot(detection.getRobot()).map(detection.getMap()).incidentType("SUSPECTED_FALL")
                                .severity(EmergencyIncidentSeverity.CRITICAL).status(EmergencyIncidentStatus.OPEN)
                                .description("Staff requested assistance after reviewing a suspected fall").xM(detection.getXM()).yM(detection.getYM())
                                .occurredAt(detection.getDetectedAt()).build());
                data.put("incident_id",incident.getIncidentId().toString());
                data.put("created",existing==null);
            }
            default -> throw new BusinessRuleException("Unsupported DATA_SERVICE service_key: "+key);
        }
        return new ServerStepExecution(result,true);
    }

    private UUID uuid(JsonNode inputs,String field){try{return UUID.fromString(inputs.path(field).asText());}catch(Exception ex){throw new BusinessRuleException(field+" must be a UUID");}}
    private ObjectNode endpoint(Endpoint e){return objectMapper.createObjectNode().put("endpoint_id",e.getEndpointId().toString()).put("endpoint_code",e.getEndpointCode()).put("endpoint_name",e.getEndpointName()).put("x_m",e.getXM()).put("y_m",e.getYM()).put("yaw_rad",e.getYawRad());}
}
