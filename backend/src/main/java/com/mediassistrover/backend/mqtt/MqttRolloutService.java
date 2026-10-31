package com.mediassistrover.backend.mqtt;

import com.mediassistrover.backend.exception.*;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.enums.*;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Durable, monotonic retirement of legacy execution; disabling MQTT cannot reopen REST. */
@Service @RequiredArgsConstructor
public class MqttRolloutService {
    public enum Mode { LEGACY_COMPATIBLE, DRAINING, MQTT_ONLY }
    public record State(Mode mode, boolean dispatchHalted, long revision, Instant updatedAt, String note) { }
    public record Change(@NotNull Mode mode, @NotNull Boolean dispatchHalted, @NotNull @PositiveOrZero Long expectedRevision,
                         @NotBlank @Size(max=1000) String note) { }
    private final JdbcTemplate jdbc;
    private final MqttProperties properties;
    private final Clock clock;

    // Independent READ_COMMITTED reads see a halt committed during a long-running Job transaction.
    @Transactional(readOnly=true,propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public State state() { return read(false); }
    private State read(boolean locked) {
        return read(locked?" FOR UPDATE":"");
    }
    private State read(String lockClause) {
        var rows=jdbc.query("SELECT mode,dispatch_halted,revision,updated_at,note FROM MqttRolloutControl WHERE control_id=1"
                +lockClause, (rs,n)->new State(Mode.valueOf(rs.getString(1)),rs.getBoolean(2),rs.getLong(3),rs.getTimestamp(4).toInstant(),rs.getString(5)));
        if(rows.size()!=1)throw new BusinessRuleException("MQTT rollout control is missing; restore the durable control row before creating or dispatching work");
        return rows.getFirst();
    }
    /** Acquire before workflow/robot selection and retain until runtime creation commits. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public State lockForRuntimeCreation() { return read(" FOR SHARE"); }
    public void requireSchema(State state,int schemaVersion) {
        if(state.mode()!=Mode.LEGACY_COMPATIBLE && schemaVersion!=3)
            throw new BusinessRuleException("MQTT rollout "+state.mode()+" requires schema 3; legacy runtime creation/publish is closed");
    }
    @Transactional(readOnly=true,propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void requireLegacyExecution() {
        if(state().mode()==Mode.MQTT_ONLY)throw new LegacyExecutionRetiredException();
    }
    @Transactional(readOnly=true,propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public boolean dispatchAllowed() { return !state().dispatchHalted(); }
    public long legacyBlockers() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM Job j WHERE (j.be_orchestrated=FALSE OR j.schema_version<>3) AND (j.status IN ('QUEUED','ACCEPTED','RUNNING','RECOVERY_REQUIRED') OR (j.reconciled_at IS NULL AND EXISTS (SELECT 1 FROM JobTask t JOIN JobStep s ON s.job_task_id=t.job_task_id WHERE t.job_id=j.job_id AND s.status='RECOVERY_REQUIRED')))",Long.class);
    }
    @Transactional
    public State change(Change input,AccountPrincipal actor) {
        if(actor==null || actor.role()!=Role.ADMIN || actor.status()!=AccountStatus.ACTIVE)throw new AccessDeniedException("Active ADMIN required");
        if(input==null || input.mode()==null || input.dispatchHalted()==null || input.expectedRevision()==null
                || input.note()==null || input.note().isBlank() || input.note().length()>1000 || input.expectedRevision()<0)
            throw new BusinessRuleException("mode, dispatchHalted, expectedRevision and note (1-1000 characters) are required");
        State old=read(true);
        if(old.revision()!=input.expectedRevision())throw new ConflictException("MQTT rollout revision changed; reload status");
        if(input.mode().ordinal()<old.mode().ordinal())throw new BusinessRuleException("Legacy retirement cannot be reversed; halt MQTT dispatch and reconcile instead of replaying over REST");
        if(old.mode()==Mode.LEGACY_COMPATIBLE && input.mode()==Mode.MQTT_ONLY)throw new BusinessRuleException("Enter DRAINING before MQTT_ONLY");
        if(input.mode()==Mode.MQTT_ONLY && old.mode()!=Mode.MQTT_ONLY) {
            long blockers=legacyBlockers();
            if(blockers>0)throw new BusinessRuleException("Cannot retire REST: "+blockers+" legacy Jobs require completion or reconciliation");
            if(!properties.isEnabled() || properties.getRobotIds().isEmpty())throw new BusinessRuleException("Configure MQTT and enrolled robots before entering MQTT_ONLY");
        }
        long revision=old.revision()+1;Timestamp now=Timestamp.from(Instant.now(clock));
        byte[] actorId=ByteBuffer.allocate(16).putLong(actor.accountId().getMostSignificantBits()).putLong(actor.accountId().getLeastSignificantBits()).array();
        jdbc.update("UPDATE MqttRolloutControl SET mode=?,dispatch_halted=?,revision=?,updated_by=?,updated_at=?,note=? WHERE control_id=1",
                input.mode().name(),input.dispatchHalted(),revision,actorId,now,input.note());
        jdbc.update("INSERT INTO MqttRolloutAudit (revision,mode,dispatch_halted,actor_id,recorded_at,note) VALUES (?,?,?,?,?,?)",
                revision,input.mode().name(),input.dispatchHalted(),actorId,now,input.note());
        return read(true);
    }
}
