package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.*;
import com.mediassistrover.backend.dto.pi.request.*;
import com.mediassistrover.backend.dto.pi.request.JobRuntimePayloads.*;
import com.mediassistrover.backend.dto.request.request.ServiceRequestRequests.*;
import com.mediassistrover.backend.dto.request.response.ServiceRequestResponses.*;
import com.mediassistrover.backend.dto.schedule.request.ScheduleRequests.ScheduleCreateRequest;
import com.mediassistrover.backend.dto.schedule.response.ScheduleResponse;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.*;
import com.mediassistrover.backend.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class RequestJobIntegrationTest extends MySqlIntegrationTest {
    @Autowired ObjectMapper objectMapper; @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc; @Autowired DeviceJwtTokenService deviceTokens;
    @Autowired AccountRepository accountRepository; @Autowired WorkflowRepository workflowRepository;
    @Autowired RobotRepository robotRepository; @Autowired JobRepository jobRepository;
    @Autowired JobStepRepository stepRepository; @Autowired StepRepository definitionStepRepository; @Autowired StepTransitionRepository transitionRepository; @Autowired JobEventRepository eventRepository;
    @Autowired ServiceRequestRepository requestRepository; @Autowired ScheduleRepository scheduleRepository;
    @Autowired HospitalInformationRepository hospitalRepository; @Autowired FloorRepository floorRepository;
    @Autowired FacilityMapRepository mapRepository; @Autowired EndpointRepository endpointRepository;
    @Autowired ServiceRequestService requestService; @Autowired PiJobRuntimeService runtimeService;
    @Autowired PiStepService piStepService; @Autowired ScheduleService scheduleService;
    @Autowired JobDispatchService dispatchService; @Autowired WorkflowRuntimeTransitionService transitionService;
    @Autowired JobService jobService; @Autowired PiIntegrationService integrationService;
    private Account actor; private AccountPrincipal principal; private Robot robot; private DevicePrincipal device;
    private UUID producerSession; private long sequence;

    @BeforeEach void setup(){clean();actor=accountRepository.save(Account.builder().accountCode("PAT-P6").username("phase6.patient").fullName("Phase 6 Patient").email("phase6.patient@example.test").role(Role.STAFF).authProvider(AuthProvider.LOCAL).passwordHash("unused").status(AccountStatus.ACTIVE).passwordChangedAt(Instant.now()).build());principal=AccountPrincipal.fromAccount(actor);producerSession=UUID.randomUUID();sequence=0;}
    @AfterEach void cleanup(){clean();}

    @Test void jobErrorDoesNotReleaseRobotWhenAnyStepRequiresRecovery(){
        publish("INFORMATION");robot=robot(null,"SCHEMA3-RECOVERY");device=device(robot);
        var request=requestService.createInformation(new InformationCreateRequest(50,"Help",objectMapper.createObjectNode()),"schema3-recovery-guard",principal,metadata());
        requestService.submit(request.serviceRequestId(),principal,metadata());
        Job job=jobRepository.findByServiceRequestServiceRequestId(request.serviceRequestId()).getFirst();
        var command=runtimeService.nextCommand(device).orElseThrow();
        runtimeService.commandAck(command.commandId(),envelope(job,null,"recovery-ack","COMMAND_ACK",new CommandAckPayload(true,null)),device);
        runtimeService.jobStarted(job.getJobId(),envelope(job,null,"recovery-start","JOB_STARTED",new JobStartedPayload(objectMapper.createObjectNode())),device);
        JobStep step=stepRepository.findByJobId(job.getJobId()).getFirst();step.setStatus(JobStepStatus.RECOVERY_REQUIRED);stepRepository.saveAndFlush(step);
        runtimeService.jobError(job.getJobId(),envelope(job,null,"recovery-error","JOB_ERROR",new JobErrorPayload("UNCERTAIN","Requires staff reconciliation",objectMapper.createObjectNode())),device);
        assertThat(jobRepository.findById(job.getJobId()).orElseThrow().getStatus()).isEqualTo(JobStatus.RECOVERY_REQUIRED);
        assertThat(robotRepository.findById(robot.getRobotId()).orElseThrow().getStatus()).isEqualTo(RobotStatus.ERROR);
    }

    @Test void informationRequestRunsEndToEndAndPiEventsAreIdempotent(){publish("INFORMATION");robot=robot(null,"P6-INFO");device=device(robot);
        InformationCreateRequest body=new InformationCreateRequest(50,"Where is radiology?",objectMapper.createObjectNode().put("question","Where is radiology?"));
        ServiceRequestResponse created=requestService.createInformation(body,"info-idem-1",principal,metadata());
        ServiceRequestResponse duplicate=requestService.createInformation(body,"info-idem-1",principal,metadata());
        assertThat(duplicate.serviceRequestId()).isEqualTo(created.serviceRequestId());
        assertThat(requestService.submit(created.serviceRequestId(),principal,metadata()).status()).isEqualTo(ServiceRequestStatus.QUEUED);
        Job job=jobRepository.findByServiceRequestServiceRequestId(created.serviceRequestId()).getFirst();
        runToCompletion(job);
        assertThat(jobRepository.findById(job.getJobId()).orElseThrow().getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(requestRepository.findById(created.serviceRequestId()).orElseThrow().getStatus()).isEqualTo(ServiceRequestStatus.COMPLETED);
        assertThat(robotRepository.findById(robot.getRobotId()).orElseThrow().getStatus()).isEqualTo(RobotStatus.IDLE);
        assertThat(eventRepository.findByJobJobId(job.getJobId(),org.springframework.data.domain.Pageable.unpaged())).isNotEmpty();
        assertThat(requestService.list(ServiceRequestStatus.COMPLETED,ServiceRequestType.INFORMATION,robot.getRobotId(),
                created.requestedAt().minusSeconds(1),Instant.now().plusSeconds(1),principal,
                org.springframework.data.domain.PageRequest.of(0,20)).getTotalElements()).isEqualTo(1);
        assertThat(jobService.list(JobStatus.SUCCEEDED,job.getWorkflow().getWorkflowId(),created.serviceRequestId(),
                robot.getRobotId(),job.getQueuedAt().minusSeconds(1),Instant.now().plusSeconds(1),principal,
                org.springframework.data.domain.PageRequest.of(0,20)).getTotalElements()).isEqualTo(1);
    }

    @Test void invalidStepResultIsRejectedWithoutCompletingTheStep(){publish("INFORMATION");robot=robot(null,"P16-CONTRACT");device=device(robot);
        ServiceRequestResponse request=requestService.createInformation(new InformationCreateRequest(50,"Help",objectMapper.createObjectNode()),"p16-invalid-result",principal,metadata());requestService.submit(request.serviceRequestId(),principal,metadata());Job job=jobRepository.findByServiceRequestServiceRequestId(request.serviceRequestId()).getFirst();
        var command=runtimeService.nextCommand(device).orElseThrow();runtimeService.commandAck(command.commandId(),envelope(job,null,"p16-command","COMMAND_ACK",new CommandAckPayload(true,null)),device);runtimeService.jobStarted(job.getJobId(),envelope(job,null,"p16-start","JOB_STARTED",new JobStartedPayload(objectMapper.createObjectNode())),device);
        JobStep query=stepRepository.findByJobId(job.getJobId()).stream().filter(s->s.getStepCodeSnapshot().equals("QUERY")).findFirst().orElseThrow();piStepService.execute(envelope(job,query,query.getOperationKey(),"STEP_SERVICE_EXECUTE",new StepServiceExecutePayload(objectMapper.createObjectNode())),device);
        JobStep answer=stepRepository.findByJobId(job.getJobId()).stream().filter(s->s.getStepCodeSnapshot().equals("ANSWER")).findFirst().orElseThrow();piStepService.execute(envelope(job,answer,answer.getOperationKey(),"STEP_SERVICE_EXECUTE",new StepServiceExecutePayload(objectMapper.createObjectNode())),device);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->runtimeService.stepResult(answer.getJobStepId(),envelope(job,answer,answer.getOperationKey(),"STEP_RESULT",new StepResultPayload(objectMapper.createObjectNode().put("ok",true))),device)).isInstanceOf(com.mediassistrover.backend.exception.BusinessRuleException.class);
        assertThat(stepRepository.findById(answer.getJobStepId()).orElseThrow().getStatus()).isEqualTo(JobStepStatus.RUNNING);
    }

    @Test void escortRequestRunsEndToEndWithPiReportedStepResults(){publish("ESCORT");FacilityMap map=facility();Endpoint endpoint=endpoint(map);robot=robot(map,"P6-ESCORT");device=device(robot);
        EscortCreateRequest body=new EscortCreateRequest(map.getMapId(),null,null,null,endpoint.getEndpointId(),null,TargetMode.ENDPOINT,"PAT-REF",EscortMode.GUIDED,new BigDecimal("0.300"),60,"Please follow",80,"Escort",objectMapper.createObjectNode());
        EscortResponse created=requestService.createEscort(body,"escort-idem-1",principal,metadata());requestService.submit(created.request().serviceRequestId(),principal,metadata());
        Job job=jobRepository.findByServiceRequestServiceRequestId(created.request().serviceRequestId()).getFirst();runToCompletion(job);
        assertThat(stepRepository.findByJobId(job.getJobId()).stream().filter(s->s.getStatus()==JobStepStatus.SUCCEEDED)).hasSize(4);
        assertThat(stepRepository.findByJobId(job.getJobId()).stream().filter(s->s.getStepCodeSnapshot().equals("HELP")).findFirst().orElseThrow().getStatus()).isEqualTo(JobStepStatus.PENDING);
        assertThat(requestRepository.findById(created.request().serviceRequestId()).orElseThrow().getStatus()).isEqualTo(ServiceRequestStatus.COMPLETED);
    }

    @Test void onceScheduleCreatesExactlyOneJobAndRetires(){Workflow workflow=publish("INFORMATION");robot=robot(null,"P6-SCHEDULE");Account admin=accountRepository.save(Account.builder().accountCode("ADM-P6").username("phase6.admin").fullName("Admin").email("phase6.admin@example.test").role(Role.ADMIN).authProvider(AuthProvider.LOCAL).passwordHash("unused").status(AccountStatus.ACTIVE).passwordChangedAt(Instant.now()).build());AccountPrincipal adminPrincipal=AccountPrincipal.fromAccount(admin);
        ScheduleResponse schedule=scheduleService.create(new ScheduleCreateRequest(workflow.getWorkflowId(),robot.getRobotId(),"ONCE-P6","One time",ScheduleType.ONCE,"Asia/Ho_Chi_Minh",Instant.now().minusSeconds(5),null,null,null,objectMapper.createObjectNode(),40,MisfirePolicy.FIRE_ONCE),adminPrincipal,metadata());
        scheduleService.activate(schedule.scheduleId(),adminPrincipal,metadata());assertThat(scheduleService.runDue(Instant.now().plusSeconds(1),10)).isEqualTo(1);assertThat(scheduleService.runDue(Instant.now().plusSeconds(2),10)).isZero();assertThat(scheduleRepository.findById(schedule.scheduleId()).orElseThrow().getStatus()).isEqualTo(ScheduleStatus.RETIRED);assertThat(jobRepository.count()).isEqualTo(1);
    }

    @Test void staffScheduleUsesServerRobotSelectionAndPauseIsIdempotent(){Workflow workflow=publish("INFORMATION");robot=robot(null,"P6-STAFF-SCHEDULE");
        ScheduleCreateRequest pinned=new ScheduleCreateRequest(workflow.getWorkflowId(),robot.getRobotId(),"STAFF-PINNED","Pinned",ScheduleType.ONCE,"Asia/Ho_Chi_Minh",Instant.now().plusSeconds(60),null,null,null,objectMapper.createObjectNode(),40,MisfirePolicy.SKIP);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->scheduleService.create(pinned,principal,metadata())).isInstanceOf(com.mediassistrover.backend.exception.BusinessRuleException.class).hasMessageContaining("server-side robot selection");
        ScheduleCreateRequest automatic=new ScheduleCreateRequest(workflow.getWorkflowId(),null,"STAFF-AUTO","Automatic",ScheduleType.ONCE,"Asia/Ho_Chi_Minh",Instant.now().plusSeconds(60),null,null,null,objectMapper.createObjectNode(),40,MisfirePolicy.SKIP);
        ScheduleResponse created=scheduleService.create(automatic,principal,metadata());assertThat(created.robotId()).isNull();
        scheduleService.activate(created.scheduleId(),principal,metadata());assertThat(scheduleService.pause(created.scheduleId(),principal,metadata()).status()).isEqualTo(ScheduleStatus.PAUSED);assertThat(scheduleService.pause(created.scheduleId(),principal,metadata()).status()).isEqualTo(ScheduleStatus.PAUSED);
    }

    @Test void deviceInteractionCreatesAnonymousRequestAndOnlyOneServerJob() throws Exception {
        publish("INFORMATION");robot=robot(null,"SCREEN-1");device=device(robot);
        InformationCreateRequest body=new InformationCreateRequest(50,"Help",objectMapper.createObjectNode());
        ServiceRequestResponse first=requestService.createDeviceInformation(body,"screen-idempotency",device,metadata());
        ServiceRequestResponse duplicate=requestService.createDeviceInformation(body,"screen-idempotency",device,metadata());
        assertThat(duplicate.serviceRequestId()).isEqualTo(first.serviceRequestId());
        assertThat(jobRepository.count()).isEqualTo(1);
        ServiceRequest saved=requestRepository.findById(first.serviceRequestId()).orElseThrow();
        assertThat(saved.getRequestedByAccount()).isNull();
        assertThat(saved.getSourceType()).isEqualTo(RequestSourceType.ROBOT_UI);
        assertThat(saved.getStatus()).isEqualTo(ServiceRequestStatus.QUEUED);
        assertThat(requestService.getDeviceRequest(first.serviceRequestId(),device).jobs()).hasSize(1);
        String bearer="Bearer "+deviceTokens.issue(robot).value();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/pi/requests/{id}",first.serviceRequestId()).header("Authorization",bearer))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.requestedByAccountId").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.jobs[0].assignedStaffAccountId").doesNotExist());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->requestService.createDeviceInformation(
                new InformationCreateRequest(51,"Different",objectMapper.createObjectNode()),"screen-idempotency",device,metadata()))
                .isInstanceOf(com.mediassistrover.backend.exception.ConflictException.class);
        DevicePrincipal another=device(robot(null,"SCREEN-2"));
        org.assertj.core.api.Assertions.assertThatThrownBy(()->requestService.createDeviceInformation(body,"screen-idempotency",another,metadata())).isInstanceOf(com.mediassistrover.backend.exception.ConflictException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->requestService.getDeviceRequest(first.serviceRequestId(),another))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        var cancelled=requestService.cancelDeviceRequest(first.serviceRequestId(),"Patient changed request","cancel-screen-1",device,metadata());
        assertThat(cancelled.status()).isEqualTo(ServiceRequestStatus.CANCELLED);
        assertThat(requestService.cancelDeviceRequest(first.serviceRequestId(),"Patient changed request","cancel-screen-1",device,metadata()).status()).isEqualTo(ServiceRequestStatus.CANCELLED);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->requestService.cancelDeviceRequest(first.serviceRequestId(),"Different reason","cancel-screen-1",device,metadata()))
                .isInstanceOf(com.mediassistrover.backend.exception.ConflictException.class);
    }

    @Test void deviceCancellationDuringRunningNavigationRequiresRecovery(){publish("NAVIGATION");FacilityMap map=facility();Endpoint endpoint=endpoint(map);robot=robot(map,"SCREEN-NAV");device=device(robot);
        NavigationCreateRequest body=new NavigationCreateRequest(map.getMapId(),null,null,null,endpoint.getEndpointId(),null,TargetMode.ENDPOINT,NavigationMode.STANDARD,null,1,null,60,"Guide patient",objectMapper.createObjectNode());
        NavigationResponse created=requestService.createDeviceNavigation(body,"device-nav-running",device,metadata());Job job=jobRepository.findByServiceRequestServiceRequestId(created.request().serviceRequestId()).getFirst();job.setStatus(JobStatus.RUNNING);job.setStartedAt(Instant.now());requestRepository.findById(created.request().serviceRequestId()).orElseThrow().setStatus(ServiceRequestStatus.IN_PROGRESS);jobRepository.saveAndFlush(job);
        var detail=requestService.cancelDeviceRequest(created.request().serviceRequestId(),"Stop now",null,device,metadata());
        assertThat(detail.status()).isEqualTo(ServiceRequestStatus.RECOVERY_REQUIRED);assertThat(detail.jobs().getFirst().status()).isEqualTo(JobStatus.RECOVERY_REQUIRED);assertThat(robotRepository.findById(robot.getRobotId()).orElseThrow().getStatus()).isEqualTo(RobotStatus.ERROR);
    }

    @Test void navigationCanPinAPublishedWorkflowAndFreeFormDataCannotOverrideEndpointIdentity(){Workflow workflow=publish("NAVIGATION");FacilityMap map=facility();Endpoint origin=endpoint(map,"P6-ORIGIN"),destination=endpoint(map,"P6-DEST");robot=robot(map,"NAV-PINNED");
        JsonNode forged=objectMapper.createObjectNode().put("workflow_id",UUID.randomUUID().toString()).put("origin_endpoint_id",UUID.randomUUID().toString())
                .put("endpoint_id",UUID.randomUUID().toString()).put("endpoint_group_id",UUID.randomUUID().toString()).put("note","preserved");
        NavigationCreateRequest body=new NavigationCreateRequest(map.getMapId(),workflow.getWorkflowId(),null,origin.getEndpointId(),destination.getEndpointId(),null,
                TargetMode.ENDPOINT,NavigationMode.STANDARD,null,1,null,60,"Pinned navigation",forged);
        NavigationResponse created=requestService.createNavigation(body,"nav-pinned-workflow",principal,metadata());
        assertThat(created.workflowId()).isEqualTo(workflow.getWorkflowId());assertThat(created.originEndpointId()).isEqualTo(origin.getEndpointId());assertThat(created.destinationEndpointId()).isEqualTo(destination.getEndpointId());
        JsonNode saved=requestRepository.findById(created.request().serviceRequestId()).orElseThrow().getRequestDataJson();
        assertThat(saved.path("workflow_id").asText()).isEqualTo(workflow.getWorkflowId().toString());assertThat(saved.path("origin_endpoint_id").asText()).isEqualTo(origin.getEndpointId().toString());
        assertThat(saved.path("endpoint_id").asText()).isEqualTo(destination.getEndpointId().toString());assertThat(saved.has("endpoint_group_id")).isFalse();assertThat(saved.path("note").asText()).isEqualTo("preserved");
        requestService.submit(created.request().serviceRequestId(),principal,metadata());
        assertThat(jobRepository.findByServiceRequestServiceRequestId(created.request().serviceRequestId()).getFirst().getWorkflow().getWorkflowId()).isEqualTo(workflow.getWorkflowId());
    }

    @Test void requestRemainsQueuedWithoutRobotAndDispatcherAssignsItAfterRobotIsReleased(){
        publish("INFORMATION");robot=robot(null,"QUEUE-1");device=device(robot);
        InformationCreateRequest body=new InformationCreateRequest(50,"Help",objectMapper.createObjectNode());
        ServiceRequestResponse first=requestService.createInformation(body,"queue-1",principal,metadata());
        requestService.submit(first.serviceRequestId(),principal,metadata());
        ServiceRequestResponse second=requestService.createInformation(body,"queue-2",principal,metadata());
        requestService.submit(second.serviceRequestId(),principal,metadata());
        Job firstJob=jobRepository.findByServiceRequestServiceRequestId(first.serviceRequestId()).getFirst();
        Job secondJob=jobRepository.findByServiceRequestServiceRequestId(second.serviceRequestId()).getFirst();
        assertThat(firstJob.getRobot()).isNotNull();
        assertThat(secondJob.getRobot()).isNull();
        assertThat(secondJob.getDispatchCommandId()).isNull();

        runToCompletion(firstJob);
        assertThat(dispatchService.dispatchQueuedJobs(10)).isEqualTo(1);
        Job assigned=jobRepository.findById(secondJob.getJobId()).orElseThrow();
        assertThat(assigned.getRobot().getRobotId()).isEqualTo(robot.getRobotId());
        assertThat(assigned.getDispatchCommandId()).isNotNull();
    }

    @Test void dispatchContainsRuntimeStepKeysAndLoopVisitCreationIsIdempotent(){publish("INFORMATION");robot=robot(null,"VISIT-1");device=device(robot);
        InformationCreateRequest body=new InformationCreateRequest(50,"Help",objectMapper.createObjectNode());ServiceRequestResponse request=requestService.createInformation(body,"visit-request",principal,metadata());requestService.submit(request.serviceRequestId(),principal,metadata());Job job=jobRepository.findByServiceRequestServiceRequestId(request.serviceRequestId()).getFirst();
        var command=runtimeService.nextCommand(device).orElseThrow();assertThat(command.jobSteps()).hasSize(stepRepository.findByJobId(job.getJobId()).size()).allMatch(s->s.jobStepId()!=null&&s.operationKey()!=null);
        runtimeService.commandAck(command.commandId(),envelope(job,null,"visit-command","COMMAND_ACK",new CommandAckPayload(true,null)),device);runtimeService.jobStarted(job.getJobId(),envelope(job,null,"visit-start","JOB_STARTED",new JobStartedPayload(objectMapper.createObjectNode())),device);
        JobStep query=stepRepository.findByJobId(job.getJobId()).stream().filter(s->s.getStepCodeSnapshot().equals("QUERY")).findFirst().orElseThrow();JobStep answer=stepRepository.findByJobId(job.getJobId()).stream().filter(s->s.getStepCodeSnapshot().equals("ANSWER")).findFirst().orElseThrow();query.getStep().setMaxVisits(2);definitionStepRepository.saveAndFlush(query.getStep());
        assertThat(piStepService.execute(envelope(job,query,query.getOperationKey(),"STEP_SERVICE_EXECUTE",new StepServiceExecutePayload(objectMapper.createObjectNode())),device).status()).isEqualTo(JobStepStatus.SUCCEEDED);
        var interact=piStepService.execute(envelope(job,answer,answer.getOperationKey(),"STEP_SERVICE_EXECUTE",new StepServiceExecutePayload(objectMapper.createObjectNode())),device);assertThat(interact.status()).isEqualTo(JobStepStatus.RUNNING);runtimeService.stepResult(answer.getJobStepId(),envelope(job,answer,answer.getOperationKey(),"STEP_RESULT",new StepResultPayload(resultFor(answer))),device);
        StepTransition answerSuccess=transitionRepository.findByFromStepStepIdAndOutcomeOrderByPriorityAsc(answer.getStep().getStepId(),TransitionOutcome.SUCCESS).stream().filter(StepTransition::isDefaultTransition).findFirst().orElseThrow();answerSuccess.setTerminalStatus(null);answerSuccess.setToStep(query.getStep());transitionRepository.saveAndFlush(answerSuccess);
        PiEnvelope<CreateStepVisitPayload> create=envelope(job,null,"visit-query-2","STEP_VISIT_CREATE",new CreateStepVisitPayload(answer.getJobStepId()));var first=runtimeService.createStepVisit(job.getJobId(),query.getStep().getStepId(),create,device);var duplicate=runtimeService.createStepVisit(job.getJobId(),query.getStep().getStepId(),create,device);
        assertThat(first.jobStepId()).isEqualTo(duplicate.jobStepId());assertThat(first.visitNo()).isEqualTo(2);assertThat(stepRepository.countByJobTaskJobJobIdAndStepStepId(job.getJobId(),query.getStep().getStepId())).isEqualTo(2);
    }

    @Test void cancelRequiresDeviceAckAndThenReleasesRobot(){publish("INFORMATION");robot=robot(null,"CANCEL-1");device=device(robot);InformationCreateRequest body=new InformationCreateRequest(50,"Help",objectMapper.createObjectNode());ServiceRequestResponse request=requestService.createInformation(body,"cancel-request",principal,metadata());requestService.submit(request.serviceRequestId(),principal,metadata());Job job=jobRepository.findByServiceRequestServiceRequestId(request.serviceRequestId()).getFirst();var dispatch=runtimeService.nextCommand(device).orElseThrow();runtimeService.commandAck(dispatch.commandId(),envelope(job,null,"cancel-dispatch","COMMAND_ACK",new CommandAckPayload(true,null)),device);runtimeService.jobStarted(job.getJobId(),envelope(job,null,"cancel-start","JOB_STARTED",new JobStartedPayload(objectMapper.createObjectNode())),device);
        jobService.cancel(job.getJobId(),new com.mediassistrover.backend.dto.job.request.JobRequests.CancelJobRequest("Operator requested cancellation"),principal,metadata());assertThat(jobRepository.findById(job.getJobId()).orElseThrow().getStatus()).isEqualTo(JobStatus.RUNNING);var cancel=runtimeService.nextCommand(device).orElseThrow();assertThat(cancel.commandType()).isEqualTo("CANCEL");runtimeService.commandAck(cancel.commandId(),envelope(job,null,"cancel-ack","COMMAND_ACK",new CommandAckPayload(true,null)),device);assertThat(jobRepository.findById(job.getJobId()).orElseThrow().getStatus()).isEqualTo(JobStatus.CANCELLED);assertThat(requestRepository.findById(request.serviceRequestId()).orElseThrow().getStatus()).isEqualTo(ServiceRequestStatus.CANCELLED);assertThat(robotRepository.findById(robot.getRobotId()).orElseThrow().getStatus()).isEqualTo(RobotStatus.IDLE);
    }

    @Test void deviceRestartDuringRunningJobRequiresManualRecovery(){publish("INFORMATION");robot=robot(null,"REBOOT-1");device=device(robot);InformationCreateRequest body=new InformationCreateRequest(50,"Help",objectMapper.createObjectNode());ServiceRequestResponse request=requestService.createInformation(body,"reboot-request",principal,metadata());requestService.submit(request.serviceRequestId(),principal,metadata());Job job=jobRepository.findByServiceRequestServiceRequestId(request.serviceRequestId()).getFirst();var dispatch=runtimeService.nextCommand(device).orElseThrow();runtimeService.commandAck(dispatch.commandId(),envelope(job,null,"reboot-dispatch","COMMAND_ACK",new CommandAckPayload(true,null)),device);runtimeService.jobStarted(job.getJobId(),envelope(job,null,"reboot-start","JOB_STARTED",new JobStartedPayload(objectMapper.createObjectNode())),device);
        HeartbeatPayload payload=new HeartbeatPayload(RobotStatus.STARTING,"1.0","1.0",1,objectMapper.createArrayNode(),null,null,null,null,BatteryUnit.UNKNOWN,Instant.now(),null);integrationService.heartbeat(envelope(job,null,"reboot-heartbeat","HEARTBEAT",payload),device);assertThat(jobRepository.findById(job.getJobId()).orElseThrow().getStatus()).isEqualTo(JobStatus.RECOVERY_REQUIRED);assertThat(requestRepository.findById(request.serviceRequestId()).orElseThrow().getStatus()).isEqualTo(ServiceRequestStatus.RECOVERY_REQUIRED);assertThat(robotRepository.findById(robot.getRobotId()).orElseThrow().getStatus()).isEqualTo(RobotStatus.ERROR);
    }

    private void runToCompletion(Job job){var command=runtimeService.nextCommand(device).orElseThrow();assertThat(command.jobSteps()).hasSize(stepRepository.findByJobId(job.getJobId()).size());runtimeService.commandAck(command.commandId(),envelope(job,null,"cmd-"+job.getJobId(),"COMMAND_ACK",new CommandAckPayload(true,null)),device);runtimeService.jobStarted(job.getJobId(),envelope(job,null,"start-"+job.getJobId(),"JOB_STARTED",new JobStartedPayload(objectMapper.createObjectNode())),device);
        JobStep current=stepRepository.findByJobId(job.getJobId()).stream().min(Comparator.comparingInt(s->s.getStep().getSequenceNo())).orElseThrow();
        while(true){StepType type=current.getStep().getStepType();if(type==StepType.DATA_SERVICE||type==StepType.INTERACT){var executed=piStepService.execute(envelope(job,current,current.getOperationKey(),"STEP_SERVICE_EXECUTE",new StepServiceExecutePayload(objectMapper.createObjectNode())),device);if(executed.status()==JobStepStatus.RUNNING){current=stepRepository.findById(current.getJobStepId()).orElseThrow();runtimeService.stepResult(current.getJobStepId(),envelope(job,current,current.getOperationKey(),"STEP_RESULT",new StepResultPayload(resultFor(current))),device);}}else{runtimeService.stepStarted(current.getJobStepId(),envelope(job,current,current.getOperationKey(),"STEP_STARTED",new StepStartedPayload(1)),device);current=stepRepository.findById(current.getJobStepId()).orElseThrow();runtimeService.stepResult(current.getJobStepId(),envelope(job,current,current.getOperationKey(),"STEP_RESULT",new StepResultPayload(resultFor(current))),device);}current=stepRepository.findById(current.getJobStepId()).orElseThrow();StepTransition route=transitionService.resolve(current);if(route.getToStep()==null){assertThat(route.getTerminalStatus()).isEqualTo(TerminalStatus.SUCCEEDED);break;}UUID targetId=route.getToStep().getStepId();current=stepRepository.findByJobId(job.getJobId()).stream().filter(s->s.getStep().getStepId().equals(targetId)&&s.getStatus()==JobStepStatus.PENDING).findFirst().orElseThrow(()->new AssertionError("No pending target "+route.getToStep().getStepCode()+"; states="+stepRepository.findByJobId(job.getJobId()).stream().map(s->s.getStepCodeSnapshot()+":"+s.getStatus()).toList()));}
        runtimeService.jobResult(job.getJobId(),envelope(job,null,"result-"+job.getJobId(),"JOB_RESULT",new JobResultPayload(objectMapper.createObjectNode().put("completed",true))),device);}
    private <T> PiEnvelope<T> envelope(Job job,JobStep step,String operation,String type,T payload){return envelope(job,step,operation,type,payload,UUID.randomUUID());}
    private com.fasterxml.jackson.databind.node.ObjectNode resultFor(JobStep step){
        JsonNode input=step.getResolvedInputsJson()==null?objectMapper.createObjectNode():step.getResolvedInputsJson();
        return switch(StepType.valueOf(step.getStepTypeSnapshot())){
            case MOVE -> objectMapper.createObjectNode().put("reached",true).put("endpoint_id",input.path("endpoint_id").asText()).put("action_status",4).putNull("last_feedback_pose").put("distance_remaining_m",0.0);
            case INTERACT -> {var result=objectMapper.createObjectNode().put("completed",true);if("CONFIRM".equals(step.getStepCodeSnapshot()))result.put("confirmed",true);else result.putNull("confirmed");result.set("input_data",objectMapper.createObjectNode());result.putNull("actor_user_id");yield result;}
            case CHECK_CONDITION -> objectMapper.createObjectNode().put("value",true);
            default -> throw new AssertionError("No Phase 16 test result fixture for StepType "+step.getStepTypeSnapshot());
        };
    }
    private <T> PiEnvelope<T> envelope(Job job,JobStep step,String operation,String type,T payload,UUID messageId){return new PiEnvelope<>("1.0",messageId,operation,UUID.randomUUID(),robot.getRobotId(),job.getJobId(),step==null?null:step.getJobStepId(),type,Instant.now(),producerSession,++sequence,payload);}
    private Workflow publish(String serviceType){Workflow w=workflowRepository.findAll().stream().filter(x->x.getSchemaVersion()==2&&x.getServiceType().equals(serviceType)).findFirst().orElseThrow();w.setStatus(WorkflowStatus.PUBLISHED);w.setAcceptsNewJobs(true);w.setDefinitionHash("a".repeat(64));w.setCompiledDefinitionJson(objectMapper.createObjectNode().put("workflow",w.getWorkflowCode()));w.setPublishedAt(Instant.now());return workflowRepository.saveAndFlush(w);}
    private Robot robot(FacilityMap map,String code){return robotRepository.saveAndFlush(Robot.builder().currentMap(map).robotCode(code).robotName(code).model("Test").protocolVersion(1).capabilitiesJson(objectMapper.createArrayNode()).status(RobotStatus.IDLE).batteryUnit(BatteryUnit.UNKNOWN).deviceSecretHash("configured-for-test").deviceTokenVersion(0).build());}
    private DevicePrincipal device(Robot r){return new DevicePrincipal(r.getRobotId(),r.getRobotCode(),0,"test-device",Instant.now().plusSeconds(3600));}
    private FacilityMap facility(){HospitalInformation h=hospitalRepository.save(HospitalInformation.builder().updatedBy(actor).infoCategory("PROFILE").title("Hospital").content("Info").languageCode("vi-VN").keywordsJson(objectMapper.createArrayNode()).status(HospitalInformationStatus.PUBLISHED).publicVisible(true).build());Floor f=floorRepository.save(Floor.builder().hospitalInformation(h).floorCode("P6-F1").floorName("Floor 1").displayOrder(1).active(true).build());return mapRepository.save(FacilityMap.builder().floor(f).mapCode("P6-MAP").versionNo(1).mapName("Map").widthM(BigDecimal.TEN).heightM(BigDecimal.TEN).resolutionMPerPixel(new BigDecimal("0.05")).originXM(BigDecimal.ZERO).originYM(BigDecimal.ZERO).originYawRad(BigDecimal.ZERO).frameId("map").mapFileUri("https://example.test/map").assetHash("b".repeat(64)).status(MapStatus.ACTIVE).build());}
    private Endpoint endpoint(FacilityMap map){return endpoint(map,"P6-DEST");}
    private Endpoint endpoint(FacilityMap map,String code){return endpointRepository.save(Endpoint.builder().map(map).endpointCode(code).endpointName("Destination").endpointType(EndpointType.WAYPOINT).xM(BigDecimal.ONE).yM(BigDecimal.ONE).yawRad(BigDecimal.ZERO).active(true).accessible(true).publicVisible(true).build());}
    private RequestMetadata metadata(){return new RequestMetadata("127.0.0.1","test","phase6-test");}
    private void clean(){jdbc.execute("SET FOREIGN_KEY_CHECKS=0");for(String t:new String[]{"JobEvent","JobStep","JobTask","Job","Schedule","NavigationRequest","EscortRequest","ServiceRequest","RobotLocationHistory","RobotStatusHistory","Robot","EndpointGroupMember","Endpoint","Room","EndpointGroup","Zone","Map","Floor","HospitalInformation","AuditLog","RevokedToken","RefreshToken","StaffProfile","Account"})jdbc.execute("TRUNCATE TABLE `"+t+"`");jdbc.update("UPDATE `Workflow` SET status='DRAFT',accepts_new_jobs=FALSE,compiled_definition_json=NULL,definition_hash=NULL,published_at=NULL,published_by_account_id=NULL,row_version=0 WHERE schema_version=2");jdbc.update("UPDATE `Step` SET max_visits=1 WHERE step_id=UNHEX(MD5('WF_INFORMATION_ASSISTANCE:STEP:QUERY'))");jdbc.update("UPDATE `StepTransition` SET to_step_id=NULL,terminal_status='SUCCEEDED' WHERE from_step_id=UNHEX(MD5('WF_INFORMATION_ASSISTANCE:STEP:ANSWER')) AND outcome='SUCCESS' AND is_default=TRUE");jdbc.execute("SET FOREIGN_KEY_CHECKS=1");}
}
