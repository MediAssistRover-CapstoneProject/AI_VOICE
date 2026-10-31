package com.mediassistrover.backend.service.impl;
import com.mediassistrover.backend.dto.schedule.request.ScheduleRequests.ScheduleCreateRequest;
import com.mediassistrover.backend.dto.schedule.response.ScheduleResponse;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.*;
import com.mediassistrover.backend.mapper.RequestJobMapper;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.*;
import com.mediassistrover.backend.validation.StaffOperationalScopePolicy;
import com.mediassistrover.backend.validation.WorkflowExecutionReadiness;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class ScheduleServiceImpl implements ScheduleService {
    private static final Set<String> STAFF_SERVICE_TYPES=Set.of("INFORMATION","NAVIGATION","ESCORT","DELIVERY");
    private final ScheduleRepository repository; private final WorkflowRepository workflowRepository;
    private final RobotRepository robotRepository; private final AccountRepository accountRepository;
    private final JobExecutionService jobExecutionService; private final RequestJobMapper mapper;
    private final AuditService auditService; private final Clock clock;
    private final StaffOperationalScopePolicy staffScope; private final WorkflowExecutionReadiness readiness;
    @Override @Transactional public ScheduleResponse create(ScheduleCreateRequest r,AccountPrincipal a,RequestMetadata md){staffScope.requireCurrentStaffOrAdmin(a);if(repository.existsByScheduleCodeIgnoreCase(r.scheduleCode()))throw new ConflictException("Schedule code already exists");validateShape(r);if(!r.inputTemplateJson().isObject())throw new BusinessRuleException("inputTemplateJson must be a JSON object");Workflow w=workflowRepository.findById(r.workflowId()).orElseThrow(()->new ResourceNotFoundException("Workflow not found: "+r.workflowId()));requireUsableWorkflow(w,a);if(a.role()==Role.STAFF&&r.robotId()!=null)throw new BusinessRuleException("Staff schedules use server-side robot selection");Robot robot=r.robotId()==null?null:robotRepository.findById(r.robotId()).orElseThrow(()->new ResourceNotFoundException("Robot not found: "+r.robotId()));Schedule s=repository.save(Schedule.builder().workflow(w).robot(robot).createdByAccount(account(a.accountId())).scheduleCode(r.scheduleCode()).scheduleName(r.scheduleName()).scheduleType(r.scheduleType()).timezone(r.timezone()).startsAt(r.startsAt()).endsAt(r.endsAt()).intervalSec(r.intervalSec()).cronExpression(r.cronExpression()).inputTemplateJson(r.inputTemplateJson()).priority(r.priority()).misfirePolicy(r.misfirePolicy()).status(ScheduleStatus.PAUSED).build());audit(a,"SCHEDULE_CREATED",s,md);return mapper.toResponse(s);}
    @Override public ScheduleResponse get(UUID id,AccountPrincipal a){Schedule s=schedule(id);scope(s,a);return mapper.toResponse(s);}
    @Override public Page<ScheduleResponse> list(ScheduleStatus status,AccountPrincipal a,Pageable p){staffScope.requireCurrentStaffOrAdmin(a);Page<Schedule> page;if(a.role()==Role.ADMIN)page=status==null?repository.findAll(p):repository.findByStatus(status,p);else page=status==null?repository.findByCreatedByAccountAccountId(a.accountId(),p):repository.findByCreatedByAccountAccountIdAndStatus(a.accountId(),status,p);return page.map(mapper::toResponse);}
    @Override @Transactional public ScheduleResponse activate(UUID id,AccountPrincipal a,RequestMetadata md){Schedule s=schedule(id);scope(s,a);if(s.getStatus()==ScheduleStatus.ACTIVE)return mapper.toResponse(s);if(s.getStatus()==ScheduleStatus.RETIRED)throw new BusinessRuleException("RETIRED schedule cannot be activated");requireUsableWorkflow(s.getWorkflow(),a);readiness.requireReady(s.getWorkflow());Instant now=Instant.now(clock);if(s.getEndsAt()!=null&&!s.getEndsAt().isAfter(now))throw new BusinessRuleException("Schedule window has already ended");if(s.getRobot()!=null&&repository.existsActiveRobotCollision(s.getRobot().getRobotId(),s.getStartsAt(),s.getEndsAt(),s.getScheduleId()))throw new ConflictException("Robot already has an overlapping ACTIVE schedule");s.setStatus(ScheduleStatus.ACTIVE);s.setNextRunAt(firstRun(s,now));audit(a,"SCHEDULE_ACTIVATED",s,md);return mapper.toResponse(s);}
    @Override @Transactional public ScheduleResponse pause(UUID id,AccountPrincipal a,RequestMetadata md){Schedule s=schedule(id);scope(s,a);if(s.getStatus()==ScheduleStatus.PAUSED)return mapper.toResponse(s);if(s.getStatus()!=ScheduleStatus.ACTIVE)throw new BusinessRuleException("Only ACTIVE schedule can be paused");s.setStatus(ScheduleStatus.PAUSED);s.setNextRunAt(null);audit(a,"SCHEDULE_PAUSED",s,md);return mapper.toResponse(s);}
    @Override @Transactional public ScheduleResponse retire(UUID id,AccountPrincipal a,RequestMetadata md){Schedule s=schedule(id);scope(s,a);if(s.getStatus()==ScheduleStatus.RETIRED)return mapper.toResponse(s);s.setStatus(ScheduleStatus.RETIRED);s.setNextRunAt(null);audit(a,"SCHEDULE_RETIRED",s,md);return mapper.toResponse(s);}
    @Override @Transactional public int runDue(Instant now,int batchSize){List<Schedule> due=repository.lockDue(now,PageRequest.of(0,batchSize));int count=0;for(Schedule s:due){Instant fire=s.getNextRunAt();if(s.getEndsAt()!=null&&fire.isAfter(s.getEndsAt())){s.setStatus(ScheduleStatus.RETIRED);s.setNextRunAt(null);continue;}jobExecutionService.createForSchedule(s,fire,s.getInputTemplateJson());s.setLastRunAt(fire);s.setNextRunAt(nextRun(s,fire));if(s.getScheduleType()==ScheduleType.ONCE||s.getNextRunAt()==null)s.setStatus(ScheduleStatus.RETIRED);count++;}return count;}
    private void validateShape(ScheduleCreateRequest r){try{ZoneId.of(r.timezone());}catch(Exception e){throw new BusinessRuleException("Invalid IANA timezone");}if(r.endsAt()!=null&&!r.endsAt().isAfter(r.startsAt()))throw new BusinessRuleException("endsAt must be after startsAt");switch(r.scheduleType()){case ONCE->{if(r.intervalSec()!=null||r.cronExpression()!=null)throw new BusinessRuleException("ONCE cannot define interval or cron");}case INTERVAL->{if(r.intervalSec()==null||r.cronExpression()!=null)throw new BusinessRuleException("INTERVAL requires intervalSec only");}case CRON->{if(r.cronExpression()==null||r.intervalSec()!=null)throw new BusinessRuleException("CRON requires cronExpression only");try{CronExpression.parse(r.cronExpression());}catch(Exception e){throw new BusinessRuleException("Invalid cron expression");}}}}
    private Instant firstRun(Schedule s,Instant now){if(s.getStartsAt().isAfter(now))return s.getStartsAt();return s.getMisfirePolicy()==MisfirePolicy.FIRE_ONCE?now:nextRun(s,now);}
    private Instant nextRun(Schedule s,Instant after){return switch(s.getScheduleType()){case ONCE->null;case INTERVAL->after.plusSeconds(s.getIntervalSec());case CRON->{ZonedDateTime next=CronExpression.parse(s.getCronExpression()).next(after.atZone(ZoneId.of(s.getTimezone())));yield next==null?null:next.toInstant();}};}
    private void scope(Schedule s,AccountPrincipal a){staffScope.requireScheduleAccess(s,a);}
    private void requireUsableWorkflow(Workflow w,AccountPrincipal a){if(w.getStatus()!=WorkflowStatus.PUBLISHED||!w.isAcceptsNewJobs())throw new BusinessRuleException("Schedule requires a published workflow accepting jobs");if(a.role()==Role.STAFF&&!STAFF_SERVICE_TYPES.contains(w.getServiceType().toUpperCase(Locale.ROOT)))throw new AccessDeniedException("Workflow service type is not available to Staff schedules");}
    private Schedule schedule(UUID id){return repository.findById(id).orElseThrow(()->new ResourceNotFoundException("Schedule not found: "+id));}
    private Account account(UUID id){return accountRepository.findById(id).orElseThrow(()->new ResourceNotFoundException("Account not found: "+id));}
    private void audit(AccountPrincipal a,String action,Schedule s,RequestMetadata md){auditService.record(account(a.accountId()),action,"Schedule",s.getScheduleId(),null,Map.of("status",s.getStatus().name()),md);}
}
