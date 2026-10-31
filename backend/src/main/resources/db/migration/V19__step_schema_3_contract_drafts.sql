-- Phase 2: preserve all schema-2 history and prepare schema-3 drafts for MQTT orchestration.
ALTER TABLE `JobStep` DROP CHECK `ck_job_step_status`;
ALTER TABLE `JobStep` ADD CONSTRAINT `ck_job_step_status`
 CHECK (`status` IN ('PENDING','RUNNING','RETRYING','SUCCEEDED','FAILED','TIMED_OUT','CANCELLED','RECOVERY_REQUIRED'));

CREATE TEMPORARY TABLE `_schema3_sources` AS
 SELECT w.workflow_id old_id, UNHEX(MD5(CONCAT(HEX(w.workflow_id),':STEP-SCHEMA-3'))) new_id,
        w.workflow_code, w.version_no+1 new_version
 FROM `Workflow` w
 WHERE w.schema_version=2 AND w.workflow_code IN (
   'WF_PATIENT_ESCORT','WF_INFORMATION_ASSISTANCE','WF_SECURE_MEDICAL_DELIVERY',
   'WF_CROWD_MONITORING','WF_EMERGENCY_SOS_RESPONSE','WF_FALL_DETECTION_RESPONSE',
   'WF_RESTRICTED_AREA_MONITORING','WF_AUTO_CHARGING','WF_NAVIGATION_RECOVERY',
   'WF_ROBOT_STARTUP_STANDBY','WF_NAVIGATION')
 AND w.version_no=(SELECT MAX(v.version_no) FROM `Workflow` v WHERE v.workflow_code=w.workflow_code);

INSERT INTO `Workflow` (`workflow_id`,`workflow_code`,`version_no`,`workflow_name`,`service_type`,`description`,
 `input_schema_json`,`input_ui_json`,`default_inputs_json`,`output_mapping_json`,`required_capabilities_json`,
 `max_duration_sec`,`max_total_visits`,`graph_version`,`schema_version`,`status`,`accepts_new_jobs`,`row_version`,`created_at`,`updated_at`)
 SELECT x.new_id,w.workflow_code,x.new_version,w.workflow_name,w.service_type,
 CONCAT('[MAR-STEP-2 DRAFT] Cloned schema 2 version ',w.version_no,'; requires MQTT orchestrator and versioned handlers.'),
 JSON_SET(w.input_schema_json,'$.properties.service_request_id',JSON_OBJECT('type','string','format','uuid'),
         '$.properties.items',JSON_OBJECT('type','array')),
 w.input_ui_json,IF(w.service_type='DELIVERY',JSON_SET(w.default_inputs_json,'$.compartment_id','CART_MAIN'),w.default_inputs_json),
 CAST(REPLACE(REPLACE(CAST(w.output_mapping_json AS CHAR),'/actor_user_id','/actor_account_id'),'/input_data','/values') AS JSON),
 w.required_capabilities_json,w.max_duration_sec,w.max_total_visits,3,3,'DRAFT',FALSE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
 FROM `Workflow` w JOIN `_schema3_sources` x ON x.old_id=w.workflow_id;

INSERT INTO `Task` (`task_id`,`workflow_id`,`task_code`,`task_name`,`sequence_no`,`description`,`config_json`,`created_at`)
 SELECT UNHEX(MD5(CONCAT(HEX(t.task_id),':STEP-SCHEMA-3'))),x.new_id,t.task_code,t.task_name,t.sequence_no,t.description,t.config_json,UTC_TIMESTAMP(6)
 FROM `Task` t JOIN `_schema3_sources` x ON x.old_id=t.workflow_id;

INSERT INTO `Step` (`step_id`,`workflow_id`,`task_id`,`step_type_code`,`step_code`,`step_name`,`sequence_no`,`description`,
 `is_enabled`,`params_json`,`input_mapping_json`,`timeout_sec`,`max_attempts`,`retry_delay_sec`,`retry_codes_json`,`max_visits`,`audit_level`,`created_at`)
 SELECT UNHEX(MD5(CONCAT(HEX(s.step_id),':STEP-SCHEMA-3'))),x.new_id,
 UNHEX(MD5(CONCAT(HEX(s.task_id),':STEP-SCHEMA-3'))),s.step_type_code,s.step_code,s.step_name,s.sequence_no,s.description,
 s.is_enabled,s.params_json,s.input_mapping_json,s.timeout_sec,s.max_attempts,s.retry_delay_sec,s.retry_codes_json,s.max_visits,s.audit_level,UTC_TIMESTAMP(6)
 FROM `Step` s JOIN `_schema3_sources` x ON x.old_id=s.workflow_id;

INSERT INTO `StepTransition` (`transition_id`,`workflow_id`,`from_step_id`,`to_step_id`,`outcome`,`condition_json`,`priority`,`is_default`,`terminal_status`)
 SELECT UNHEX(MD5(CONCAT(HEX(t.transition_id),':STEP-SCHEMA-3'))),x.new_id,
 UNHEX(MD5(CONCAT(HEX(t.from_step_id),':STEP-SCHEMA-3'))),
 IF(t.to_step_id IS NULL,NULL,UNHEX(MD5(CONCAT(HEX(t.to_step_id),':STEP-SCHEMA-3')))),
 t.outcome,t.condition_json,t.priority,t.is_default,t.terminal_status
 FROM `StepTransition` t JOIN `_schema3_sources` x ON x.old_id=t.workflow_id;

UPDATE `Workflow` n JOIN `_schema3_sources` x ON x.new_id=n.workflow_id JOIN `Workflow` o ON o.workflow_id=x.old_id
 SET n.start_step_id=IF(o.start_step_id IS NULL,NULL,UNHEX(MD5(CONCAT(HEX(o.start_step_id),':STEP-SCHEMA-3'))));

UPDATE `Step` s JOIN `_schema3_sources` x ON x.new_id=s.workflow_id
 SET s.params_json=JSON_MERGE_PATCH(JSON_OBJECT('mode','NORMAL','max_speed_mps',0.05),s.params_json)
 WHERE s.step_type_code='MOVE';

-- Business data for UI/alerts moves inside data; recipient routing remains BE configuration.
UPDATE `Step` s JOIN `_schema3_sources` x ON x.new_id=s.workflow_id
 SET s.input_mapping_json=JSON_OBJECT('data',JSON_MERGE_PATCH(COALESCE(JSON_EXTRACT(s.params_json,'$.data'),JSON_OBJECT()),s.input_mapping_json)),
     s.params_json=JSON_SET(s.params_json,'$.action',COALESCE(JSON_UNQUOTE(JSON_EXTRACT(s.params_json,'$.action')),'DISPLAY'))
 WHERE s.step_type_code='INTERACT';

UPDATE `Step` s JOIN `_schema3_sources` x ON x.new_id=s.workflow_id
 SET s.input_mapping_json=JSON_OBJECT('data',JSON_MERGE_PATCH(COALESCE(JSON_EXTRACT(s.params_json,'$.data'),JSON_OBJECT()),s.input_mapping_json)),
     s.params_json=JSON_SET(s.params_json,'$.severity',COALESCE(JSON_UNQUOTE(JSON_EXTRACT(s.params_json,'$.severity')),'INFO'),
       '$.require_ack',COALESCE(JSON_EXTRACT(s.params_json,'$.require_ack'),CAST('false' AS JSON)))
 WHERE s.step_type_code='NOTIFY';

UPDATE `Step` s JOIN `_schema3_sources` x ON x.new_id=s.workflow_id
 SET s.input_mapping_json=JSON_SET(JSON_REMOVE(s.input_mapping_json,'$.incident_id'),'$.correlation_key',
     COALESCE(JSON_EXTRACT(s.input_mapping_json,'$.correlation_key'),JSON_EXTRACT(s.input_mapping_json,'$.incident_id'),
       JSON_OBJECT('$ref','/inputs/service_request_id')))
 WHERE s.step_type_code='WAIT' AND JSON_UNQUOTE(JSON_EXTRACT(s.params_json,'$.wait_type'))='EVENT';

UPDATE `Step` s JOIN `_schema3_sources` x ON x.new_id=s.workflow_id
 SET s.params_json=JSON_SET(s.params_json,'$.action',IF(s.step_code LIKE '%RECEIVER%','UNLOAD','LOAD')),
     s.input_mapping_json=JSON_SET(s.input_mapping_json,'$.items',JSON_OBJECT('$ref','/inputs/items'))
 WHERE s.step_type_code='HANDLE_PAYLOAD';

UPDATE `Step` s JOIN `_schema3_sources` x ON x.new_id=s.workflow_id
 SET s.input_mapping_json=JSON_REMOVE(s.input_mapping_json,'$.service_request_id'),
     s.params_json=JSON_SET(s.params_json,'$.action',CASE JSON_UNQUOTE(JSON_EXTRACT(s.params_json,'$.action'))
       WHEN 'LOCK' THEN 'LOCK_COMPARTMENT' WHEN 'UNLOCK' THEN 'UNLOCK_COMPARTMENT'
       ELSE JSON_UNQUOTE(JSON_EXTRACT(s.params_json,'$.action')) END),s.max_attempts=1
 WHERE s.step_type_code='CONTROL_HARDWARE';

DROP TEMPORARY TABLE `_schema3_sources`;
-- Definitions are compiled by Schema3DraftCompilationInitializer after Flyway, without publishing.
