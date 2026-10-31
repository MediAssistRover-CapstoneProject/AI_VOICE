-- New standalone admin mapping Job; no legacy request type/StepType is redefined.
SET @mapping_workflow=UNHEX(MD5('WF_MAPPING:WORKFLOW:1'));
SET @mapping_task=UNHEX(MD5('WF_MAPPING:TASK:1'));
INSERT INTO `Workflow` (`workflow_id`,`workflow_code`,`version_no`,`workflow_name`,`service_type`,`description`,
 `input_schema_json`,`input_ui_json`,`default_inputs_json`,`output_mapping_json`,`required_capabilities_json`,
 `max_duration_sec`,`max_total_visits`,`graph_version`,`schema_version`,`status`,`accepts_new_jobs`,`row_version`,`created_at`,`updated_at`)
VALUES (@mapping_workflow,'WF_MAPPING',1,'Mapping and verified map artifact','MAPPING',
 '[MAR-STEP-2 DRAFT] Phase 12 mapping template; requires mappingEnabled Pi and upload commissioning.',
 CAST('{"type":"object","properties":{"floor_id":{"type":"string","format":"uuid"},"map_name":{"type":"string","minLength":1,"maxLength":160}},"required":["floor_id","map_name"],"additionalProperties":false}' AS JSON),
 JSON_OBJECT(),JSON_OBJECT(),JSON_OBJECT('map_id',JSON_OBJECT('$ref','/outputs/COMMIT_MAP/data/map_id')),JSON_ARRAY(),1800,10,3,3,'DRAFT',FALSE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO `Task` (`task_id`,`workflow_id`,`task_code`,`task_name`,`sequence_no`,`config_json`,`created_at`)
VALUES (@mapping_task,@mapping_workflow,'MAPPING','Mapping session',1,JSON_OBJECT(),UTC_TIMESTAMP(6));
INSERT INTO `Step` (`step_id`,`workflow_id`,`task_id`,`step_type_code`,`step_code`,`step_name`,`sequence_no`,`is_enabled`,
 `params_json`,`input_mapping_json`,`timeout_sec`,`max_attempts`,`retry_delay_sec`,`retry_codes_json`,`max_visits`,`audit_level`,`created_at`)
VALUES
 (UNHEX(MD5('WF_MAPPING:START')),@mapping_workflow,@mapping_task,'EXECUTE_SENSING','START_MAPPING','Start SLAM',1,TRUE,
 JSON_OBJECT('sensing_type','MAPPING','action','START','preview_rate_hz',1),JSON_OBJECT('mapping_session_id',JSON_OBJECT('$ref','/vars/mapping_session_id')),60,1,0,JSON_ARRAY(),1,'FULL',UTC_TIMESTAMP(6)),
 (UNHEX(MD5('WF_MAPPING:WAIT')),@mapping_workflow,@mapping_task,'WAIT','WAIT_SAVE','Wait for staff Save',2,TRUE,
 JSON_OBJECT('wait_type','EVENT','event_type','MAPPING_SAVE_REQUESTED'),JSON_OBJECT('correlation_key',JSON_OBJECT('$ref','/vars/mapping_session_id')),1500,1,0,JSON_ARRAY(),1,'FULL',UTC_TIMESTAMP(6)),
 (UNHEX(MD5('WF_MAPPING:SAVE')),@mapping_workflow,@mapping_task,'EXECUTE_SENSING','SAVE_MAPPING','Save and upload map',3,TRUE,
 JSON_OBJECT('sensing_type','MAPPING','action','SAVE'),JSON_OBJECT('mapping_session_id',JSON_OBJECT('$ref','/vars/mapping_session_id'),'artifact_id',JSON_OBJECT('$ref','/vars/artifact_id')),90,1,0,JSON_ARRAY(),1,'FULL',UTC_TIMESTAMP(6)),
 (UNHEX(MD5('WF_MAPPING:COMMIT')),@mapping_workflow,@mapping_task,'DATA_SERVICE','COMMIT_MAP','Commit verified artifact',4,TRUE,
 JSON_OBJECT('service_key','COMMIT_MAP_ARTIFACT'),JSON_OBJECT('mapping_session_id',JSON_OBJECT('$ref','/vars/mapping_session_id'),'artifact_id',JSON_OBJECT('$ref','/vars/artifact_id'),
 'floor_id',JSON_OBJECT('$ref','/inputs/floor_id'),'map_name',JSON_OBJECT('$ref','/inputs/map_name')),30,1,0,JSON_ARRAY(),1,'FULL',UTC_TIMESTAMP(6)),
 (UNHEX(MD5('WF_MAPPING:STOP')),@mapping_workflow,@mapping_task,'EXECUTE_SENSING','STOP_MAPPING','Stop SLAM',5,TRUE,
 JSON_OBJECT('sensing_type','MAPPING','action','STOP'),JSON_OBJECT('mapping_session_id',JSON_OBJECT('$ref','/vars/mapping_session_id')),20,1,0,JSON_ARRAY(),1,'FULL',UTC_TIMESTAMP(6));
UPDATE `Workflow` SET start_step_id=UNHEX(MD5('WF_MAPPING:START')) WHERE workflow_id=@mapping_workflow;
INSERT INTO `StepTransition` (`transition_id`,`workflow_id`,`from_step_id`,`to_step_id`,`outcome`,`priority`,`is_default`,`terminal_status`)
SELECT UNHEX(MD5(CONCAT('WF_MAPPING:',s.step_code,':',o.outcome))),@mapping_workflow,s.step_id,
 IF(o.outcome='SUCCESS',n.step_id,NULL),o.outcome,1,TRUE,
 IF(o.outcome='SUCCESS',IF(n.step_id IS NULL,'SUCCEEDED',NULL),IF(s.step_code='STOP_MAPPING','RECOVERY_REQUIRED','FAILED'))
FROM `Step` s LEFT JOIN `Step` n ON n.workflow_id=s.workflow_id AND n.sequence_no=s.sequence_no+1
CROSS JOIN (SELECT 'SUCCESS' outcome UNION ALL SELECT 'FAILURE' UNION ALL SELECT 'TIMEOUT') o
WHERE s.workflow_id=@mapping_workflow;
