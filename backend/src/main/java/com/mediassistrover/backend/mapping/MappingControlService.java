package com.mediassistrover.backend.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.*;
import com.mediassistrover.backend.mqtt.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.validation.MqttContractValidator;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Short manual pulses are never stored in the command outbox or replayed. */
@Service @RequiredArgsConstructor
public class MappingControlService {
    private final JobRepository jobs;
    private final MappingSessionRepository mappingSessions;
    private final RobotMqttSessionRepository robotSessions;
    private final MqttSessionRegistry registry;
    private final MqttProperties mqtt;
    private final MappingProperties mapping;
    private final PahoMqttTransport transport;
    private final MqttWire wire;
    private final MqttContractValidator contracts;
    private final Clock clock;
    // Epoch-based start also advances across normal BE restarts. Pi rejects old sequences.
    private final AtomicLong sequence=new AtomicLong(System.currentTimeMillis()*1000);
    private final Map<UUID,Receipt> latest=new ConcurrentHashMap<>();
    private record ClientKey(UUID robot,UUID mapping,UUID client) { }
    private record ClientCursor(long sequence,Instant seenAt) { }
    private final Map<ClientKey,ClientCursor> clientOrder=new ConcurrentHashMap<>();
    public record Request(@NotNull UUID mappingSessionId,
            @NotNull @DecimalMin("-0.30") @DecimalMax("0.30") Double linearX,
            @NotNull @DecimalMin("-1.0") @DecimalMax("1.0") Double angularZ,
            @Min(100) @Max(900) int durationMs,
            UUID controlSessionId,@Min(1) Long controlSequence,Instant requestedAt) {
        public Request(UUID mappingSessionId,Double linearX,Double angularZ,int durationMs) {
            this(mappingSessionId,linearX,angularZ,durationMs,null,null,null);
        }
    }
    public record Receipt(UUID commandId,UUID jobId,UUID mappingSessionId,UUID transportSessionId,
            long sequenceNo,Instant issuedAt,Instant expiresAt,String status,String errorCode,Instant receivedAt) { }

    public record Autonomy(@NotNull String action,@NotNull UUID runId,
            @Min(10) @Max(600) int maxDurationSec,boolean stopOnReturn,boolean saveOnComplete) {
        public Autonomy(String action,UUID runId,int maxDurationSec,boolean stopOnReturn){this(action,runId,maxDurationSec,stopOnReturn,false);}
    }
    public record AutonomyRequest(@NotNull UUID mappingSessionId,
            @NotNull @jakarta.validation.Valid Autonomy autonomy,
            @NotNull UUID controlSessionId,@NotNull @Min(1) Long controlSequence,@NotNull Instant requestedAt) { }

    @Transactional
    public Receipt send(UUID jobId,Request input) { return publish(jobId,input,null); }

    @Transactional
    public Receipt sendAutonomy(UUID jobId,AutonomyRequest input) {
        Autonomy options=input.autonomy();
        if(options==null || options.runId()==null || options.action()==null || !Set.of("START","RENEW","STOP").contains(options.action())
                || options.maxDurationSec()<10 || options.maxDurationSec()>600 || input.controlSessionId()==null
                || input.controlSequence()==null || input.requestedAt()==null)
            throw new BusinessRuleException("Invalid autonomy action, scope or duration");
        return publish(jobId,new Request(input.mappingSessionId(),0.,0.,900,input.controlSessionId(),
                input.controlSequence(),input.requestedAt()),options);
    }

    private Receipt publish(UUID jobId,Request input,Autonomy autonomy) {
        if(!mapping.isEnabled() || !mqtt.isDispatchEnabled())throw new BusinessRuleException("Mapping dispatch is disabled");
        Job job=jobs.lockById(jobId).orElseThrow(()->new ResourceNotFoundException("Job not found"));
        MappingSession session=mappingSessions.findByJobId(jobId).orElseThrow(()->new BusinessRuleException("Job has no mapping session"));
        if(!session.getSessionId().equals(input.mappingSessionId()) || !session.isPotentiallyOpen())
            throw new BusinessRuleException("Mapping session is closed or does not belong to Job");
        boolean stop=input.linearX()!=null && input.angularZ()!=null && input.linearX()==0 && input.angularZ()==0
                && (autonomy==null || "STOP".equals(autonomy.action()));
        if(input.linearX()==null || input.angularZ()==null || !Double.isFinite(input.linearX()) || !Double.isFinite(input.angularZ())
                || Math.abs(input.linearX())>.30 || Math.abs(input.angularZ())>1.0 || input.durationMs()<100 || input.durationMs()>900)
            throw new BusinessRuleException("Invalid mapping velocity or duration");
        JobStep selected=job.getSelectedJobStep();
        if(!stop && (job.getStatus()!=JobStatus.RUNNING || job.getCancelRequestedAt()!=null || !"MAPPING".equals(session.getState())
                || selected==null || selected.getStatus()!=JobStepStatus.RUNNING || !"WAIT".equals(selected.getStepTypeSnapshot())
                || !"MAPPING_SAVE_REQUESTED".equals(selected.getResolvedInputsJson().path("event_type").asText())))
            throw new BusinessRuleException("Move only while mapping Job waits for MAPPING_SAVE_REQUESTED");
        UUID robot=session.getRobotId();
        RobotMqttSession link=robotSessions.findById(robot).orElse(null);
        if(!mqtt.manages(robot) || !transport.connected() || !registry.fresh(link)
                || !link.getHelloJson().path("mappingEnabled").asBoolean())throw new BusinessRuleException("Pi mapping connection is not ready");
        if(!stop && autonomy==null && link.getHeartbeatJson()!=null
                && "gamepad".equals(link.getHeartbeatJson().at("/mappingSession/autonomy/manual_control/source").asText()))
            throw new BusinessRuleException("Local gamepad owns manual movement; STOP and autonomy remain available");
        if(autonomy!=null && !stop) {
            JsonNode report=link.getHeartbeatJson()==null?null:link.getHeartbeatJson().path("mappingSession");
            if(report==null || !session.getSessionId().toString().equals(report.path("session_id").asText())
                    || !report.path("autonomy").path("available").asBoolean())
                throw new BusinessRuleException("Pi autonomy is not configured for this mapping session; install/configure the Pi patch first");
        }
        // Keep manual-control timestamps compatible with Python 3.10 robots.
        // Durable commands pass through DB precision; this QoS 0 lane does not.
        Instant now=Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        if(input.requestedAt()!=null && (input.requestedAt().isBefore(now.minusMillis(1500)) || input.requestedAt().isAfter(now.plusSeconds(5))))
            throw new BusinessRuleException("Stale UI control request; check laptop/server time");
        if((input.controlSessionId()==null)!=(input.controlSequence()==null))
            throw new BusinessRuleException("controlSessionId and controlSequence must be supplied together");
        if(input.controlSequence()!=null) {
            if(input.controlSequence()<1 || input.controlSequence()>9_007_199_254_740_991L)
                throw new BusinessRuleException("Invalid UI control sequence");
            clientOrder.entrySet().removeIf(entry->entry.getValue().seenAt().isBefore(now.minusSeconds(3600)));
            ClientKey key=new ClientKey(robot,session.getSessionId(),input.controlSessionId());
            if(!clientOrder.containsKey(key) && clientOrder.size()>=256)throw new BusinessRuleException("Too many UI control sessions");
            clientOrder.compute(key,(ignored,prior)->{
                if(prior!=null && input.controlSequence()<=prior.sequence())throw new BusinessRuleException("Stale UI control sequence");
                return new ClientCursor(input.controlSequence(),now);
            });
        }
        Receipt prior=latest.get(robot);
        if(!stop && prior!=null && prior.issuedAt().isAfter(now.minusMillis(240)))
            throw new BusinessRuleException("Wait at least 250 ms between movement commands");
        var receipt=new Receipt(UUID.randomUUID(),jobId,session.getSessionId(),link.getTransportSessionId(),sequence.incrementAndGet(),
                now,now.plusMillis(1500),"QUEUED",null,null);
        var command=wire.object().put("messageType","MAPPING_CONTROL").put("robotId",robot.toString())
                .put("transportSessionId",link.getTransportSessionId().toString()).put("jobId",jobId.toString())
                .put("mappingSessionId",session.getSessionId().toString()).put("commandId",receipt.commandId().toString())
                .put("sequenceNo",receipt.sequenceNo()).put("issuedAt",now.toString()).put("expiresAt",receipt.expiresAt().toString())
                .put("linearX",input.linearX()).put("angularZ",input.angularZ()).put("durationMs",input.durationMs());
        if(autonomy!=null)command.set("autonomy",wire.object().put("action",autonomy.action())
                .put("runId",autonomy.runId().toString()).put("maxDurationSec",autonomy.maxDurationSec())
                .put("stopOnReturn",autonomy.stopOnReturn()).put("saveOnComplete",autonomy.saveOnComplete()));
        contracts.live(command);latest.put(robot,receipt);
        transport.publish(MqttTopics.topic(robot,"mapping/control"),wire.bytes(command),0).whenComplete((ignored,error)->
                latest.computeIfPresent(robot,(id,current)->!current.commandId().equals(receipt.commandId()) || !"QUEUED".equals(current.status())?current:
                        update(current,error==null?"MQTT_PUBLISHED":"PUBLISH_FAILED",error==null?null:"MQTT_PUBLISH_FAILED",null)));
        return receipt;
    }
    public void acknowledge(UUID robot,JsonNode ack) {
        RobotMqttSession link=robotSessions.findById(robot).orElse(null);
        if(!registry.fresh(link) || !link.getTransportSessionId().toString().equals(ack.path("transportSessionId").asText())
                || !link.getProducerSessionId().toString().equals(ack.path("producerSessionId").asText()))return;
        latest.computeIfPresent(robot,(id,current)->{
            if(!current.commandId().toString().equals(ack.path("commandId").asText())
                    || !current.jobId().toString().equals(ack.path("jobId").asText())
                    || !current.mappingSessionId().toString().equals(ack.path("mappingSessionId").asText())
                    || current.sequenceNo()!=ack.path("sequenceNo").asLong())return current;
            return update(current,ack.path("accepted").asBoolean()?"PI_ACCEPTED":"PI_REJECTED",
                    ack.path("errorCode").isNull()?null:ack.path("errorCode").asText(),Instant.now(clock));
        });
    }
    public Receipt status(UUID jobId) {
        var session=mappingSessions.findByJobId(jobId).orElseThrow(()->new ResourceNotFoundException("Mapping session not found"));
        Receipt receipt=latest.get(session.getRobotId());
        if(receipt==null || !receipt.jobId().equals(jobId))return null;
        if(Set.of("QUEUED","MQTT_PUBLISHED").contains(receipt.status()) && receipt.expiresAt().plusSeconds(2).isBefore(Instant.now(clock)))
            return update(receipt,"NO_PI_ACK","NO_PI_ACK",null);
        return receipt;
    }
    private Receipt update(Receipt r,String status,String error,Instant received) {
        return new Receipt(r.commandId(),r.jobId(),r.mappingSessionId(),r.transportSessionId(),r.sequenceNo(),r.issuedAt(),r.expiresAt(),status,error,received);
    }
}
