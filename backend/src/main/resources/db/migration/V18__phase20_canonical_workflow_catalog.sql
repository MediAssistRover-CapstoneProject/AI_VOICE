-- Phase 20: retain historical templates and add the canonical S4 catalog as new DRAFT versions.
ALTER TABLE `ServiceRequest` DROP CHECK `ck_service_request_type`;
ALTER TABLE `ServiceRequest` ADD CONSTRAINT `ck_service_request_type` CHECK (`request_type` IN (
  'INFORMATION','NAVIGATION','ESCORT','DELIVERY','EMERGENCY_SOS','CROWD_MONITORING',
  'FALL_DETECTION_RESPONSE','RESTRICTED_AREA_MONITORING','AUTO_CHARGING',
  'NAVIGATION_RECOVERY','ROBOT_STARTUP_STANDBY'));

-- Canonical names replace two old aliases without mutating or deleting their history.
INSERT INTO `Workflow` (`workflow_id`,`workflow_code`,`version_no`,`workflow_name`,`service_type`,`description`,
 `input_schema_json`,`input_ui_json`,`default_inputs_json`,`output_mapping_json`,`required_capabilities_json`,
 `max_duration_sec`,`max_total_visits`,`graph_version`,`schema_version`,`status`,`accepts_new_jobs`,`row_version`,`created_at`,`updated_at`)
SELECT UNHEX(MD5(CONCAT(x.new_code,':WORKFLOW:3'))),x.new_code,3,x.new_name,x.service_type,
 CONCAT('Canonical S4 draft copied from historical alias ',w.workflow_code),w.input_schema_json,w.input_ui_json,
 w.default_inputs_json,w.output_mapping_json,w.required_capabilities_json,w.max_duration_sec,w.max_total_visits,3,2,
 'DRAFT',FALSE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM `Workflow` w JOIN (
 SELECT 'WF_FALL_RESPONSE' old_code,'WF_FALL_DETECTION_RESPONSE' new_code,'Fall detection response' new_name,'FALL_DETECTION_RESPONSE' service_type
 UNION ALL SELECT 'WF_RESTRICTED_MONITORING','WF_RESTRICTED_AREA_MONITORING','Restricted area monitoring','RESTRICTED_AREA_MONITORING'
) x ON x.old_code=w.workflow_code
WHERE w.version_no=1
  AND NOT EXISTS (SELECT 1 FROM `Workflow` n WHERE n.workflow_code=x.new_code AND n.version_no=3);

INSERT INTO `Task` (`task_id`,`workflow_id`,`task_code`,`task_name`,`sequence_no`,`description`,`config_json`,`created_at`)
SELECT UNHEX(MD5(CONCAT(n.workflow_code,':TASK:',t.task_code))),n.workflow_id,t.task_code,t.task_name,t.sequence_no,t.description,t.config_json,UTC_TIMESTAMP(6)
FROM `Workflow` n JOIN (
 SELECT 'WF_FALL_RESPONSE' old_code,'WF_FALL_DETECTION_RESPONSE' new_code
 UNION ALL SELECT 'WF_RESTRICTED_MONITORING','WF_RESTRICTED_AREA_MONITORING'
) x ON x.new_code=n.workflow_code
JOIN `Workflow` o ON o.workflow_code=x.old_code AND o.version_no=1
JOIN `Task` t ON t.workflow_id=o.workflow_id WHERE n.version_no=3;

INSERT INTO `Step` (`step_id`,`workflow_id`,`task_id`,`step_type_code`,`step_code`,`step_name`,`sequence_no`,`description`,
 `is_enabled`,`params_json`,`input_mapping_json`,`timeout_sec`,`max_attempts`,`retry_delay_sec`,`retry_codes_json`,`max_visits`,`audit_level`,`created_at`)
SELECT UNHEX(MD5(CONCAT(n.workflow_code,':STEP:',s.step_code))),n.workflow_id,
 UNHEX(MD5(CONCAT(n.workflow_code,':TASK:',t.task_code))),s.step_type_code,s.step_code,s.step_name,s.sequence_no,s.description,
 s.is_enabled,s.params_json,s.input_mapping_json,s.timeout_sec,1,0,JSON_ARRAY(),s.max_visits,s.audit_level,UTC_TIMESTAMP(6)
FROM `Workflow` n JOIN (
 SELECT 'WF_FALL_RESPONSE' old_code,'WF_FALL_DETECTION_RESPONSE' new_code
 UNION ALL SELECT 'WF_RESTRICTED_MONITORING','WF_RESTRICTED_AREA_MONITORING'
) x ON x.new_code=n.workflow_code
JOIN `Workflow` o ON o.workflow_code=x.old_code AND o.version_no=1
JOIN `Step` s ON s.workflow_id=o.workflow_id JOIN `Task` t ON t.task_id=s.task_id WHERE n.version_no=3;

INSERT INTO `StepTransition` (`transition_id`,`workflow_id`,`from_step_id`,`to_step_id`,`outcome`,`condition_json`,`priority`,`is_default`,`terminal_status`)
SELECT UNHEX(MD5(CONCAT(n.workflow_code,':TRANSITION:',fs.step_code,':',st.outcome,':',st.priority))),n.workflow_id,
 UNHEX(MD5(CONCAT(n.workflow_code,':STEP:',fs.step_code))),
 IF(ts.step_code IS NULL,NULL,UNHEX(MD5(CONCAT(n.workflow_code,':STEP:',ts.step_code)))),st.outcome,st.condition_json,st.priority,st.is_default,st.terminal_status
FROM `Workflow` n JOIN (
 SELECT 'WF_FALL_RESPONSE' old_code,'WF_FALL_DETECTION_RESPONSE' new_code
 UNION ALL SELECT 'WF_RESTRICTED_MONITORING','WF_RESTRICTED_AREA_MONITORING'
) x ON x.new_code=n.workflow_code
JOIN `Workflow` o ON o.workflow_code=x.old_code AND o.version_no=1
JOIN `StepTransition` st ON st.workflow_id=o.workflow_id JOIN `Step` fs ON fs.step_id=st.from_step_id
LEFT JOIN `Step` ts ON ts.step_id=st.to_step_id WHERE n.version_no=3;

UPDATE `Workflow` n JOIN (
 SELECT 'WF_FALL_RESPONSE' old_code,'WF_FALL_DETECTION_RESPONSE' new_code
 UNION ALL SELECT 'WF_RESTRICTED_MONITORING','WF_RESTRICTED_AREA_MONITORING'
) x ON x.new_code=n.workflow_code
JOIN `Workflow` o ON o.workflow_code=x.old_code AND o.version_no=1
JOIN `Step` os ON os.step_id=o.start_step_id
SET n.start_step_id=UNHEX(MD5(CONCAT(n.workflow_code,':STEP:',os.step_code))) WHERE n.version_no=3;

UPDATE `Workflow` SET input_schema_json=CAST('{"type":"object","required":["detection_event_id","confidence","min_confidence"],"properties":{"detection_event_id":{"type":"string"},"confidence":{"type":"number"},"min_confidence":{"type":"number"}}}' AS JSON)
WHERE workflow_code='WF_FALL_DETECTION_RESPONSE' AND version_no=3;
UPDATE `Workflow` SET input_schema_json=CAST('{"type":"object","required":["map_id","checkpoint_id","zone_id"],"properties":{"map_id":{"type":"string"},"checkpoint_id":{"type":"string"},"zone_id":{"type":"string"}}}' AS JSON)
WHERE workflow_code='WF_RESTRICTED_AREA_MONITORING' AND version_no=3;

-- Three S4 workflows that were absent from the original eight-template catalog.
INSERT INTO `Workflow` (`workflow_id`,`workflow_code`,`version_no`,`workflow_name`,`service_type`,`description`,
 `input_schema_json`,`input_ui_json`,`default_inputs_json`,`output_mapping_json`,`required_capabilities_json`,
 `max_duration_sec`,`max_total_visits`,`graph_version`,`schema_version`,`status`,`accepts_new_jobs`,`row_version`,`created_at`,`updated_at`) VALUES
(UNHEX(MD5('WF_AUTO_CHARGING:WORKFLOW:3')),'WF_AUTO_CHARGING',3,'Manual robot charging request','AUTO_CHARGING','Requests Staff charging; it never claims automatic docking or battery full.',
 CAST('{"type":"object","required":["robot_id","charge_request_id"],"properties":{"robot_id":{"type":"string"},"charge_request_id":{"type":"string"}}}' AS JSON),JSON_OBJECT(),JSON_OBJECT(),CAST('{"charge_request_id":{"$ref":"/inputs/charge_request_id"},"staff_event":{"$ref":"/outputs/WAIT_STAFF/event"},"health":{"$ref":"/outputs/HEALTH/metrics"},"automatic_charging":false}' AS JSON),CAST('["NOTIFICATION","EVENT_WAIT","SENSORS"]' AS JSON),2100,100,3,2,'DRAFT',FALSE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),
(UNHEX(MD5('WF_NAVIGATION_RECOVERY:WORKFLOW:3')),'WF_NAVIGATION_RECOVERY',3,'Navigation recovery','NAVIGATION_RECOVERY','A new reconciled job that retries MOVE exactly once after PATH_CLEARED.',
 CAST('{"type":"object","required":["map_id","destination_endpoint_id","recovery_request_id","recovery_of_job_id"],"properties":{"map_id":{"type":"string"},"destination_endpoint_id":{"type":"string"},"recovery_request_id":{"type":"string"},"recovery_of_job_id":{"type":"string"}}}' AS JSON),JSON_OBJECT(),JSON_OBJECT(),CAST('{"recovery_request_id":{"$ref":"/inputs/recovery_request_id"},"endpoint_id":{"$ref":"/outputs/RETRY/endpoint_id"},"reached":{"$ref":"/outputs/RETRY/reached"}}' AS JSON),CAST('["NAVIGATION","NOTIFICATION","EVENT_WAIT"]' AS JSON),1200,100,3,2,'DRAFT',FALSE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),
(UNHEX(MD5('WF_ROBOT_STARTUP_STANDBY:WORKFLOW:3')),'WF_ROBOT_STARTUP_STANDBY',3,'Robot startup readiness','ROBOT_STARTUP_STANDBY','Finite startup sensor check; does not model standby as an infinite WAIT.',
 CAST('{"type":"object","properties":{},"additionalProperties":false}' AS JSON),JSON_OBJECT(),JSON_OBJECT(),CAST('{"health":{"$ref":"/outputs/HEALTH/metrics"},"ready":{"$ref":"/outputs/CHECK/value"}}' AS JSON),CAST('["SENSORS","NOTIFICATION"]' AS JSON),1200,100,3,2,'DRAFT',FALSE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

CREATE TEMPORARY TABLE `p20_step` (
 `wf` VARCHAR(80),`code` VARCHAR(80),`type` VARCHAR(40),`seq` INT,`params` JSON,`mapping` JSON,`timeout_sec` INT,
 `success_to` VARCHAR(80),`success_terminal` VARCHAR(32),`failure_to` VARCHAR(80),`failure_terminal` VARCHAR(32),
 `timeout_to` VARCHAR(80),`timeout_terminal` VARCHAR(32)
);
INSERT INTO `p20_step` VALUES
('WF_AUTO_CHARGING','REQUEST','NOTIFY',1,CAST('{"notification_type":"MANUAL_CHARGE_REQUIRED","severity":"WARNING","recipients":["STAFF"],"require_ack":false}' AS JSON),CAST('{"robot_id":{"$ref":"/inputs/robot_id"},"charge_request_id":{"$ref":"/inputs/charge_request_id"}}' AS JSON),60,'WAIT_STAFF',NULL,NULL,'FAILED',NULL,'FAILED'),
('WF_AUTO_CHARGING','WAIT_STAFF','WAIT',2,CAST('{"wait_type":"EVENT","event_type":"STAFF_CHARGE_COMPLETED"}' AS JSON),CAST('{"correlation_key":{"$ref":"/inputs/charge_request_id"}}' AS JSON),1800,'HEALTH',NULL,NULL,'FAILED',NULL,'FAILED'),
('WF_AUTO_CHARGING','HEALTH','EXECUTE_SENSING',3,CAST('{"sensing_type":"ROBOT_HEALTH"}' AS JSON),JSON_OBJECT(),30,'DONE',NULL,NULL,'FAILED',NULL,'FAILED'),
('WF_AUTO_CHARGING','DONE','NOTIFY',4,CAST('{"notification_type":"STAFF_REPORTED_CHARGE_COMPLETED","severity":"INFO","recipients":["STAFF"],"require_ack":false}' AS JSON),CAST('{"staff_event":{"$ref":"/outputs/WAIT_STAFF/event"},"health":{"$ref":"/outputs/HEALTH/metrics"}}' AS JSON),60,NULL,'SUCCEEDED',NULL,'FAILED',NULL,'FAILED'),
('WF_NAVIGATION_RECOVERY','ALERT','NOTIFY',1,CAST('{"notification_type":"PATH_BLOCKED","severity":"WARNING","recipients":["STAFF"],"require_ack":false}' AS JSON),CAST('{"recovery_request_id":{"$ref":"/inputs/recovery_request_id"},"endpoint_id":{"$ref":"/inputs/destination_endpoint_id"}}' AS JSON),60,'WAIT',NULL,NULL,'FAILED',NULL,'FAILED'),
('WF_NAVIGATION_RECOVERY','WAIT','WAIT',2,CAST('{"wait_type":"EVENT","event_type":"PATH_CLEARED"}' AS JSON),CAST('{"correlation_key":{"$ref":"/inputs/recovery_request_id"}}' AS JSON),120,'RETRY',NULL,NULL,'FAILED',NULL,'FAILED'),
('WF_NAVIGATION_RECOVERY','RETRY','MOVE',3,CAST('{"mode":"NORMAL","max_speed_mps":0.15}' AS JSON),CAST('{"map_id":{"$ref":"/inputs/map_id"},"endpoint_id":{"$ref":"/inputs/destination_endpoint_id"}}' AS JSON),180,'DONE',NULL,'HELP',NULL,'HELP',NULL),
('WF_NAVIGATION_RECOVERY','DONE','NOTIFY',4,CAST('{"notification_type":"NAVIGATION_RECOVERED","severity":"INFO","recipients":["STAFF"],"require_ack":false}' AS JSON),CAST('{"recovery_request_id":{"$ref":"/inputs/recovery_request_id"},"endpoint_id":{"$ref":"/inputs/destination_endpoint_id"}}' AS JSON),60,NULL,'SUCCEEDED',NULL,'FAILED',NULL,'FAILED'),
('WF_NAVIGATION_RECOVERY','HELP','NOTIFY',5,CAST('{"notification_type":"NAVIGATION_REQUIRES_STAFF","severity":"CRITICAL","recipients":["STAFF"],"require_ack":true,"ack_policy":"ANY"}' AS JSON),CAST('{"recovery_request_id":{"$ref":"/inputs/recovery_request_id"},"endpoint_id":{"$ref":"/inputs/destination_endpoint_id"}}' AS JSON),60,NULL,'RECOVERY_REQUIRED',NULL,'FAILED',NULL,'FAILED'),
('WF_ROBOT_STARTUP_STANDBY','HEALTH','EXECUTE_SENSING',1,CAST('{"sensing_type":"ROBOT_HEALTH"}' AS JSON),JSON_OBJECT(),30,'CHECK',NULL,NULL,'FAILED',NULL,'FAILED'),
('WF_ROBOT_STARTUP_STANDBY','CHECK','CHECK_CONDITION',2,CAST('{"expression":{"path":"/outputs/HEALTH/metrics/sensors_ready","operator":"EQ","value":true}}' AS JSON),JSON_OBJECT(),30,NULL,'RECOVERY_REQUIRED',NULL,'RECOVERY_REQUIRED',NULL,'RECOVERY_REQUIRED'),
('WF_ROBOT_STARTUP_STANDBY','READY','NOTIFY',3,CAST('{"notification_type":"ROBOT_READY","severity":"INFO","recipients":["STAFF"],"require_ack":false}' AS JSON),CAST('{"health":{"$ref":"/outputs/HEALTH/metrics"}}' AS JSON),60,NULL,'SUCCEEDED',NULL,'FAILED',NULL,'FAILED');

INSERT INTO `Task` (`task_id`,`workflow_id`,`task_code`,`task_name`,`sequence_no`,`config_json`,`created_at`)
SELECT UNHEX(MD5(CONCAT(w.workflow_code,':TASK:MAIN'))),w.workflow_id,'MAIN','Main',1,JSON_OBJECT(),UTC_TIMESTAMP(6)
FROM `Workflow` w WHERE w.version_no=3 AND w.workflow_code IN ('WF_AUTO_CHARGING','WF_NAVIGATION_RECOVERY','WF_ROBOT_STARTUP_STANDBY');

INSERT INTO `Step` (`step_id`,`workflow_id`,`task_id`,`step_type_code`,`step_code`,`step_name`,`sequence_no`,`is_enabled`,`params_json`,`input_mapping_json`,`timeout_sec`,`max_attempts`,`retry_delay_sec`,`retry_codes_json`,`max_visits`,`audit_level`,`created_at`)
SELECT UNHEX(MD5(CONCAT(p.wf,':STEP:',p.code))),w.workflow_id,UNHEX(MD5(CONCAT(p.wf,':TASK:MAIN'))),p.type,p.code,p.code,p.seq,TRUE,p.params,p.mapping,p.timeout_sec,1,0,JSON_ARRAY(),10,'FULL',UTC_TIMESTAMP(6)
FROM `p20_step` p JOIN `Workflow` w ON w.workflow_code=p.wf AND w.version_no=3;

INSERT INTO `StepTransition` (`transition_id`,`workflow_id`,`from_step_id`,`to_step_id`,`outcome`,`condition_json`,`priority`,`is_default`,`terminal_status`)
SELECT UNHEX(MD5(CONCAT(p.wf,':TRANSITION:',p.code,':',o.outcome,':',IF(p.wf='WF_ROBOT_STARTUP_STANDBY' AND p.code='CHECK' AND o.outcome='SUCCESS',2,1)))),w.workflow_id,
 UNHEX(MD5(CONCAT(p.wf,':STEP:',p.code))),
 IF(CASE o.outcome WHEN 'SUCCESS' THEN p.success_to WHEN 'FAILURE' THEN p.failure_to ELSE p.timeout_to END IS NULL,NULL,
    UNHEX(MD5(CONCAT(p.wf,':STEP:',CASE o.outcome WHEN 'SUCCESS' THEN p.success_to WHEN 'FAILURE' THEN p.failure_to ELSE p.timeout_to END)))),
 o.outcome,NULL,IF(p.wf='WF_ROBOT_STARTUP_STANDBY' AND p.code='CHECK' AND o.outcome='SUCCESS',2,1),TRUE,CASE o.outcome WHEN 'SUCCESS' THEN p.success_terminal WHEN 'FAILURE' THEN p.failure_terminal ELSE p.timeout_terminal END
FROM `p20_step` p JOIN `Workflow` w ON w.workflow_code=p.wf AND w.version_no=3
CROSS JOIN (SELECT 'SUCCESS' outcome UNION ALL SELECT 'FAILURE' UNION ALL SELECT 'TIMEOUT') o;

INSERT INTO `StepTransition` (`transition_id`,`workflow_id`,`from_step_id`,`to_step_id`,`outcome`,`condition_json`,`priority`,`is_default`,`terminal_status`)
SELECT UNHEX(MD5('WF_ROBOT_STARTUP_STANDBY:TRANSITION:CHECK:SUCCESS:1')),w.workflow_id,
 UNHEX(MD5('WF_ROBOT_STARTUP_STANDBY:STEP:CHECK')),UNHEX(MD5('WF_ROBOT_STARTUP_STANDBY:STEP:READY')),
 'SUCCESS',CAST('{"path":"result.value","operator":"EQ","value":true}' AS JSON),1,FALSE,NULL
FROM `Workflow` w WHERE w.workflow_code='WF_ROBOT_STARTUP_STANDBY' AND w.version_no=3;

UPDATE `Workflow` w SET w.start_step_id=UNHEX(MD5(CONCAT(w.workflow_code,':STEP:',CASE w.workflow_code
 WHEN 'WF_AUTO_CHARGING' THEN 'REQUEST' WHEN 'WF_NAVIGATION_RECOVERY' THEN 'ALERT' ELSE 'HEALTH' END)))
WHERE w.version_no=3 AND w.workflow_code IN ('WF_AUTO_CHARGING','WF_NAVIGATION_RECOVERY','WF_ROBOT_STARTUP_STANDBY');

DROP TEMPORARY TABLE `p20_step`;
