package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Small committed graph used by DB tests and the real TLS broker test. */
final class MqttTestFixture {
    UUID robotId,workflowId,jobId,piStepId,endStepId,mapId,endpointId,floorId,hospitalId,accountId,serviceRequestId,commandId;
    UUID transportId=UUID.randomUUID(),producerId=UUID.randomUUID();long sequence;
    StepType type;String sensing;String operationKey;
    static MqttTestFixture create(EntityManager em,ObjectMapper json,StepType type,String sensing) {
        var f=new MqttTestFixture();f.type=type;f.sensing=sensing;String code=UUID.randomUUID().toString();Instant now=Instant.now();
        FacilityMap map=null;Account actor=null;
        boolean delivery=Set.of(StepType.AUTHENTICATE,StepType.HANDLE_PAYLOAD).contains(type);
        if (type==StepType.MOVE || delivery) {
            actor=Account.builder().accountCode("MQ-"+code).username("mq-"+code).fullName("MQTT test").role(Role.ADMIN).authProvider(AuthProvider.LOCAL).status(AccountStatus.ACTIVE).passwordHash("unused").build();em.persist(actor);f.accountId=actor.getAccountId();
            HospitalInformation info=HospitalInformation.builder().updatedBy(actor).infoCategory("GENERAL").title("MQTT test").content("Test").languageCode("vi-VN").keywordsJson(json.createArrayNode()).status(HospitalInformationStatus.PUBLISHED).build();em.persist(info);f.hospitalId=info.getHospitalInformationId();
            Floor floor=Floor.builder().hospitalInformation(info).floorCode("F-"+code).floorName("Test").displayOrder(1).active(true).build();em.persist(floor);f.floorId=floor.getFloorId();
            map=FacilityMap.builder().floor(floor).mapCode("M-"+code).mapName("Test").versionNo(1).widthM(BigDecimal.TEN).heightM(BigDecimal.TEN)
                    .resolutionMPerPixel(new BigDecimal("0.05")).originXM(BigDecimal.ZERO).originYM(BigDecimal.ZERO).originYawRad(BigDecimal.ZERO).frameId("map")
                    .mapFileUri("test://map").assetHash("a".repeat(64)).status(MapStatus.ACTIVE).build();em.persist(map);f.mapId=map.getMapId();
            Endpoint endpoint=Endpoint.builder().map(map).endpointCode("E-"+code).endpointName("Test").endpointType(EndpointType.WAYPOINT).xM(BigDecimal.ONE).yM(BigDecimal.ONE).yawRad(BigDecimal.ZERO).active(true).accessible(true).build();em.persist(endpoint);f.endpointId=endpoint.getEndpointId();
        }
        Robot robot=Robot.builder().robotCode("MQ-"+code).robotName("Test").model("Test").status(RobotStatus.IDLE).protocolVersion(1).currentMap(map).mapAssetHash(map==null?null:map.getAssetHash()).capabilitiesJson(json.createArrayNode()).build();em.persist(robot);f.robotId=robot.getRobotId();
        ServiceRequest request=null;
if(delivery) {
    ObjectNode requestData=json.createObjectNode();
    requestData.putArray("items")
            .addObject()
            .put("item_code","MED-1")
            .put("quantity",1);

    request=ServiceRequest.builder()
            .requestedByAccount(actor)
            .requestCode("REQ-"+code)
            .requestType(ServiceRequestType.DELIVERY)
            .sourceType(RequestSourceType.STAFF_UI)
            .priority(50)
            .status(ServiceRequestStatus.QUEUED)
            .requestDataJson(requestData)
            .idempotencyKey("mq-"+code)
            .requestedAt(now)
            .build();

    em.persist(request);
    em.flush();
    f.serviceRequestId=request.getServiceRequestId();

    Endpoint deliveryEndpoint = em.find(Endpoint.class, f.endpointId);

    DeliveryRequest deliveryRequest = DeliveryRequest.builder()
            .serviceRequest(request)
            .map(map)
            .pickupEndpoint(deliveryEndpoint)
            .deliveryEndpoint(deliveryEndpoint)
            .targetMode(TargetMode.ENDPOINT)
            .recipientName("MQTT test")
            .requiresConfirmation(true)
            .build();

    em.persist(deliveryRequest);

    em.persist(DeliveryItem.builder()
            .deliveryRequest(deliveryRequest)
            .itemCode("MED-1")
            .itemName("Medicine")
            .itemCategory("MEDICINE")
            .quantity(1)
            .unit("BOX")
            .build());
}
        Workflow workflow=Workflow.builder().workflowCode("MQ-"+code).workflowName("Test").serviceType("TEST").versionNo(1).schemaVersion(3).graphVersion(3).maxDurationSec(600).maxTotalVisits(10)
                .inputSchemaJson(json.createObjectNode()).inputUiJson(json.createObjectNode()).defaultInputsJson(json.createObjectNode()).outputMappingJson(json.createObjectNode()).requiredCapabilitiesJson(json.createArrayNode())
                .status(WorkflowStatus.DRAFT).definitionHash("a".repeat(64)).compiledDefinitionJson(json.createObjectNode()).build();em.persist(workflow);f.workflowId=workflow.getWorkflowId();
        Task task=Task.builder().workflow(workflow).taskCode("TASK").taskName("Test").sequenceNo(1).configJson(json.createObjectNode()).build();em.persist(task);
        ObjectNode params=json.createObjectNode();
        switch (type) {
            case INTERACT -> { if("SPEAK".equals(sensing)){ params.put("prompt_key","DEMO_SPEAK").put("action","SPEAK");params.putObject("data").put("text","Xin chào").put("language_code","vi"); } else params.put("prompt_key","WELCOME").put("action","DISPLAY").set("data",json.createObjectNode()); }
            case WAIT -> params.put("wait_type","DURATION").put("duration_sec",2);
            case EXECUTE_SENSING -> params.put("sensing_type",sensing);
            case CONTROL_HARDWARE -> params.put("compartment_id","CART_MAIN").put("action","LOCK_COMPARTMENT");
            case MOVE -> params.put("map_id",f.mapId.toString()).put("endpoint_id",f.endpointId.toString()).put("mode","NORMAL").put("max_speed_mps",0.05);
            case DATA_SERVICE -> params.put("service_key","QUERY_HOSPITAL_INFORMATION").put("question","Visiting hours?").put("language_code","vi-VN");
            case AUTHENTICATE -> params.put("purpose","DELIVERY_SENDER").put("service_request_id",f.serviceRequestId.toString());
            case HANDLE_PAYLOAD -> {params.put("action","LOAD").put("service_request_id",f.serviceRequestId.toString()).put("compartment_id","CART_MAIN");params.putArray("items").addObject().put("item_code","MED-1").put("quantity",1);}
            case NOTIFY -> params.put("notification_type","TEST").put("severity","INFO").put("require_ack",false).set("data",json.createObjectNode());
            case CHECK_CONDITION -> params.set("expression",json.createObjectNode().put("path","/inputs/flag").put("operator","EQ").put("value",true));
            case SET_CONTEXT -> params.putArray("operations").addObject().put("path","/vars/remote_value").put("value",1);
        }
        Step pi=definition(workflow,task,type,"PI",1,params,json);em.persist(pi);workflow.setStartStep(pi);
        ObjectNode set=json.createObjectNode();set.putArray("operations").addObject().put("path","/vars/done").put("value",true);
        Step end=definition(workflow,task,StepType.SET_CONTEXT,"END",2,set,json);em.persist(end);
        em.persist(StepTransition.builder().workflow(workflow).fromStep(pi).toStep(end).outcome(TransitionOutcome.SUCCESS).priority(1).defaultTransition(true).build());
        for (TransitionOutcome outcome:List.of(TransitionOutcome.FAILURE,TransitionOutcome.TIMEOUT)) em.persist(StepTransition.builder().workflow(workflow).fromStep(pi).outcome(outcome).priority(1).defaultTransition(true).terminalStatus(TerminalStatus.FAILED).build());
        em.persist(StepTransition.builder().workflow(workflow).fromStep(end).outcome(TransitionOutcome.SUCCESS).priority(1).defaultTransition(true).terminalStatus(TerminalStatus.SUCCEEDED).build());
        ObjectNode jobInput=json.createObjectNode().put("flag",true);if(delivery)jobInput.set("items",request.getRequestDataJson().path("items").deepCopy());
        Job job=Job.builder().workflow(workflow).serviceRequest(request).robot(robot).map(map).jobCode("MQ-"+code).status(JobStatus.QUEUED).priority(50).beOrchestrated(true).schemaVersion(3).graphVersion(3).protocolVersion(1)
                .inputJson(jobInput).contextJson(json.createObjectNode()).workflowHash("a".repeat(64)).queuedAt(now).build();em.persist(job);f.jobId=job.getJobId();
        JobTask runtime=JobTask.builder().job(job).task(task).sequenceNo(1).taskNameSnapshot("Test").status(JobTaskStatus.PENDING).configSnapshotJson(json.createObjectNode()).build();em.persist(runtime);
        for (Step step:List.of(pi,end)) {
            JobStep js=JobStep.builder().jobTask(runtime).step(step).visitNo(1).executionSequenceNo(step.getSequenceNo()).stepCodeSnapshot(step.getStepCode()).stepTypeSnapshot(step.getStepType().name())
                    .resolvedInputsJson(json.createObjectNode()).status(JobStepStatus.PENDING).attemptNo(1).timeoutSecSnapshot(60).operationKey(job.getJobId()+":"+step.getStepCode()+":1").build();em.persist(js);
            if (step==pi) {f.piStepId=js.getJobStepId();f.operationKey=js.getOperationKey();} else f.endStepId=js.getJobStepId();
        }
        em.flush();return f;
    }
    static Step definition(Workflow w,Task t,StepType type,String code,int seq,ObjectNode params,ObjectMapper json) {
        return Step.builder().workflow(w).task(t).stepType(type).stepCode(code).stepName(code).sequenceNo(seq).enabled(true).paramsJson(params).inputMappingJson(json.createObjectNode()).timeoutSec(60).maxAttempts(1).retryDelaySec(0).retryCodesJson(json.createArrayNode()).maxVisits(1).auditLevel(StepAuditLevel.FULL).build();
    }
    ObjectNode hello(ObjectMapper json) {
        return hello(json, EnumSet.of(StepType.MOVE,StepType.WAIT,StepType.CONTROL_HARDWARE,StepType.EXECUTE_SENSING,StepType.INTERACT));
    }
    ObjectNode hello(ObjectMapper json, Set<StepType> advertised) {
        ObjectNode payload=json.createObjectNode().put("contract","MAR-STEP-2").put("runtimeVersion","0.7.0").put("simulation",true).put("unlockPolicy","DISABLED").put("mappingEnabled",false);
        var enabled=payload.putArray("enabledStepTypes");for(StepType stepType:advertised)enabled.add(stepType.name());payload.putArray("capabilities");payload.putArray("states");payload.putNull("mappingSession");
        return system(json,"HELLO",payload);
    }
    ObjectNode heartbeat(ObjectMapper json) {
        ObjectNode payload=json.createObjectNode().put("status","IDLE").put("runtimeVersion","0.7.0").put("protocolMajor",1);payload.putArray("capabilities");payload.putNull("activeJobStepId");
        if (mapId==null) {payload.putNull("currentMapId");payload.putNull("mapAssetHash");} else payload.put("currentMapId",mapId.toString()).put("mapAssetHash","a".repeat(64));
        payload.putNull("mappingSession");payload.set("telemetry",json.createObjectNode());return system(json,"HEARTBEAT",payload);
    }
    ObjectNode system(ObjectMapper json,String type,ObjectNode p) {ObjectNode e=event(json,type,p);e.putNull("jobId");e.putNull("jobStepId");e.put("operationKey",e.path("messageId").asText());e.put("correlationId",UUID.randomUUID().toString());return e;}
    ObjectNode event(ObjectMapper json,String type,ObjectNode p) {
        ObjectNode e=json.createObjectNode().put("protocolVersion","1.0").put("messageId",UUID.randomUUID().toString()).put("operationKey",operationKey).put("correlationId",(commandId==null?UUID.randomUUID():commandId).toString())
                .put("robotId",robotId.toString()).put("jobId",jobId.toString()).put("jobStepId",piStepId.toString()).put("messageType",type).put("occurredAt",Instant.now().toString())
                .put("producerSessionId",producerId.toString()).put("sequenceNo",++sequence).put("transportSessionId",transportId.toString());e.set("payload",p);return e;
    }
    ObjectNode output(ObjectMapper json) {
        if (type==StepType.INTERACT && "SPEAK".equals(sensing)) {var result=json.createObjectNode().put("completed",true).put("action","SPEAK").put("prompt_key","DEMO_SPEAK").put("source","AUDIO_CLIENT").put("completed_at",Instant.now().toString());result.putNull("actor_account_id");result.putObject("values").put("playback_completed",true).put("audible_verified",false);return result;}
        if (type==StepType.WAIT) {var result=json.createObjectNode().put("completed",true).put("wake_reason","DURATION").put("elapsed_sec",2);result.putNull("event");return result;}
        if(type==StepType.DATA_SERVICE){var data=json.createObjectNode().put("found",false).put("answer","Unavailable").put("language_code","vi-VN").put("count",0).putNull("source").put("fallback_code","INFORMATION_UNAVAILABLE");data.putArray("items");return json.createObjectNode().put("request_id",operationKey).set("data",data);}
        if(type==StepType.AUTHENTICATE)return json.createObjectNode().put("verified",true).put("verification_id",UUID.randomUUID().toString()).put("actor_account_id",accountId.toString()).put("purpose","DELIVERY_SENDER").put("service_request_id",serviceRequestId.toString()).put("verified_at",Instant.now().minusSeconds(1).toString()).put("valid_until",Instant.now().plusSeconds(300).toString());
        if(type==StepType.HANDLE_PAYLOAD){var result=json.createObjectNode().put("confirmed",true).put("action","LOAD").put("service_request_id",serviceRequestId.toString()).put("compartment_id","CART_MAIN");result.putArray("items").addObject().put("item_code","MED-1").put("quantity",1);return result.put("actor_account_id",accountId.toString()).put("confirmed_at",Instant.now().toString()).put("source","STAFF_MANUAL");}
        if(type==StepType.NOTIFY)return json.createObjectNode().put("alert_id",UUID.randomUUID().toString()).put("notification_type","TEST").put("persisted",true).put("requires_ack",false);
        if(type==StepType.CHECK_CONDITION)return json.createObjectNode().put("value",true);
        if(type==StepType.SET_CONTEXT)return json.createObjectNode().set("updated",json.createObjectNode().put("remote_value",1));
        try {
            JsonNode cases=json.readTree(java.nio.file.Path.of("docs/schemas/fixtures/contract-cases.json").toFile());
            for (JsonNode c:cases) if(c.path("valid").asBoolean() && c.path("schema").asText().equals("pi-step-outputs.schema.json") && c.path("instance").path("stepType").asText().equals(type.name())) {
                ObjectNode result=(ObjectNode)c.path("instance").path("result").deepCopy();
                if (type==StepType.EXECUTE_SENSING && !result.path("sensing_type").asText().equals(sensing)) continue;
                if (type==StepType.CONTROL_HARDWARE && !result.path("action").asText().equals("LOCK_COMPARTMENT")) continue;
                if (type==StepType.MOVE) {result.put("endpoint_id",endpointId.toString());for (var attempt:result.path("attempts")) ((ObjectNode)attempt).put("endpoint_id",endpointId.toString());}
                return result;
            }
            throw new IllegalStateException("No fixture output");
        } catch(java.io.IOException ex) {throw new IllegalStateException(ex);}
    }
    static void cleanup(EntityManager em,MqttTestFixture f) {
        em.createNativeQuery("DELETE FROM MqttOperationRecoveryReceipt WHERE job_step_id IN (SELECT s.job_step_id FROM JobStep s JOIN JobTask t ON s.job_task_id=t.job_task_id WHERE t.job_id=:id)").setParameter("id",f.jobId).executeUpdate();
        em.createNativeQuery("DELETE FROM JobNavigationSelection WHERE selector_job_step_id IN (SELECT s.job_step_id FROM JobStep s JOIN JobTask t ON t.job_task_id=s.job_task_id WHERE t.job_id=:id)").setParameter("id",f.jobId).executeUpdate();
        em.createNativeQuery("DELETE FROM AlertRecipient WHERE alert_id IN (SELECT alert_id FROM Alert WHERE job_step_id IN (SELECT s.job_step_id FROM JobStep s JOIN JobTask t ON s.job_task_id=t.job_task_id WHERE t.job_id=:id))").setParameter("id",f.jobId).executeUpdate();
        em.createNativeQuery("DELETE FROM Alert WHERE job_step_id IN (SELECT s.job_step_id FROM JobStep s JOIN JobTask t ON s.job_task_id=t.job_task_id WHERE t.job_id=:id)").setParameter("id",f.jobId).executeUpdate();
        em.createNativeQuery("DELETE FROM AuditLog WHERE entity_id=:id").setParameter("id",f.jobId).executeUpdate();
        for (String sql:List.of("UPDATE Job SET selected_job_step_id=NULL WHERE job_id=:id","DELETE FROM RobotCommandOutbox WHERE job_id=:id","DELETE FROM JobEvent WHERE job_id=:id","DELETE FROM WorkflowOrchestrationWork WHERE job_id=:id",
                "DELETE FROM JobStep WHERE job_task_id IN (SELECT job_task_id FROM JobTask WHERE job_id=:id)","DELETE FROM JobTask WHERE job_id=:id","DELETE FROM Job WHERE job_id=:id")) em.createNativeQuery(sql).setParameter("id",f.jobId).executeUpdate();
        for (String table:List.of("RobotNavigationProfile","RobotMqttInbox","RobotMqttSession","RobotLocationHistory","RobotStatusHistory")) em.createNativeQuery("DELETE FROM "+table+" WHERE robot_id=:id").setParameter("id",f.robotId).executeUpdate();
        em.createNativeQuery("DELETE FROM Robot WHERE robot_id=:id").setParameter("id",f.robotId).executeUpdate();
        em.createNativeQuery("UPDATE Workflow SET start_step_id=NULL WHERE workflow_id=:id").setParameter("id",f.workflowId).executeUpdate();
        for (String table:List.of("StepTransition","Step","Task","Workflow")) em.createNativeQuery("DELETE FROM "+table+" WHERE workflow_id=:id").setParameter("id",f.workflowId).executeUpdate();
        if (f.mapId!=null) {
            if(f.serviceRequestId!=null) {
                em.createNativeQuery("DELETE FROM DeliveryItem WHERE delivery_request_id=:request").setParameter("request",f.serviceRequestId).executeUpdate();
                em.createNativeQuery("DELETE FROM DeliveryRequest WHERE service_request_id=:request").setParameter("request",f.serviceRequestId).executeUpdate();
                em.createNativeQuery("DELETE FROM ServiceRequest WHERE service_request_id=:request").setParameter("request",f.serviceRequestId).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM Endpoint WHERE map_id=:id").setParameter("id",f.mapId).executeUpdate();
            em.createNativeQuery("DELETE FROM Map WHERE map_id=:id").setParameter("id",f.mapId).executeUpdate();
            em.createNativeQuery("DELETE FROM Floor WHERE floor_id=:id").setParameter("id",f.floorId).executeUpdate();
            em.createNativeQuery("DELETE FROM HospitalInformation WHERE hospital_information_id=:id").setParameter("id",f.hospitalId).executeUpdate();
            em.createNativeQuery("DELETE FROM Account WHERE account_id=:id").setParameter("id",f.accountId).executeUpdate();
        }
        em.clear();
    }
}
