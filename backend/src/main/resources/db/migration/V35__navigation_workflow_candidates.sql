-- Safe catalog addition only. These workflows deliberately remain DRAFT until an ADMIN
-- validates/compiles/publishes them and the referenced endpoints pass Nav2 commissioning.
-- No endpoint coordinate is seeded here: the repository contains one mapper-test candidate
-- (3.5000, 2.1250), but no room/door/footprint/reachability evidence for 14 live targets.

CREATE TEMPORARY TABLE `_v35_navigation_source` AS
SELECT `workflow_id`
FROM `Workflow`
WHERE `workflow_code` = 'WF_NAVIGATION' AND `schema_version` = 3
ORDER BY `version_no` DESC
LIMIT 1;

INSERT INTO `Workflow` (`workflow_id`,`workflow_code`,`version_no`,`workflow_name`,`service_type`,`description`,
 `input_schema_json`,`input_ui_json`,`default_inputs_json`,`output_mapping_json`,`required_capabilities_json`,
 `max_duration_sec`,`max_total_visits`,`graph_version`,`schema_version`,`status`,`accepts_new_jobs`,`row_version`,`created_at`,`updated_at`)
SELECT UNHEX(MD5(CONCAT(c.workflow_code,':WORKFLOW:1'))),c.workflow_code,1,c.workflow_name,'NAVIGATION',
 CONCAT('[MAR-STEP-2 DRAFT] ',c.description),
 JSON_SET(s.input_schema_json,
   '$.type','object',
   '$.required',CAST(IF(c.workflow_code='WF_DYNAMIC_ENDPOINT_NAVIGATION',
       '["map_id","target_mode","endpoint_id"]','["map_id","target_mode","endpoint_group_id"]') AS JSON),
   '$.properties.map_id',JSON_OBJECT('type','string','format','uuid'),
   '$.properties.target_mode',JSON_OBJECT('type','string','enum',CAST(IF(c.workflow_code='WF_DYNAMIC_ENDPOINT_NAVIGATION','["ENDPOINT"]','["ENDPOINT_GROUP"]') AS JSON)),
   '$.properties.endpoint_id',JSON_OBJECT('type','string','format','uuid'),
   '$.properties.endpoint_group_id',JSON_OBJECT('type','string','format','uuid')),
 s.input_ui_json,s.default_inputs_json,s.output_mapping_json,s.required_capabilities_json,
 s.max_duration_sec,s.max_total_visits,s.graph_version,s.schema_version,'DRAFT',FALSE,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM `_v35_navigation_source` x JOIN `Workflow` s ON s.workflow_id=x.workflow_id
JOIN (
 SELECT 'WF_DYNAMIC_ENDPOINT_NAVIGATION' workflow_code,'Dynamic endpoint navigation' workflow_name,
        'Runtime destination comes only from the Navigation Request endpoint UUID; pending Admin validation/publish.' description
 UNION ALL
 SELECT 'WF_HOSPITAL_MULTI_STOP','Hospital multi-stop navigation',
        'Sequential endpoint-group route; every MOVE waits for the prior operation result; pending endpoint commissioning and Admin validation/publish.'
) c
WHERE NOT EXISTS (SELECT 1 FROM `Workflow` e WHERE e.workflow_code=c.workflow_code AND e.version_no=1);

INSERT INTO `Task` (`task_id`,`workflow_id`,`task_code`,`task_name`,`sequence_no`,`description`,`config_json`,`created_at`)
SELECT UNHEX(MD5(CONCAT(n.workflow_code,':TASK:',t.task_code))),n.workflow_id,t.task_code,t.task_name,t.sequence_no,t.description,t.config_json,UTC_TIMESTAMP(6)
FROM `_v35_navigation_source` x JOIN `Task` t ON t.workflow_id=x.workflow_id
JOIN `Workflow` n ON n.workflow_code IN ('WF_DYNAMIC_ENDPOINT_NAVIGATION','WF_HOSPITAL_MULTI_STOP') AND n.version_no=1
WHERE NOT EXISTS (SELECT 1 FROM `Task` e WHERE e.workflow_id=n.workflow_id AND e.task_code=t.task_code);

INSERT INTO `Step` (`step_id`,`workflow_id`,`task_id`,`step_type_code`,`step_code`,`step_name`,`sequence_no`,`description`,
 `is_enabled`,`params_json`,`input_mapping_json`,`timeout_sec`,`max_attempts`,`retry_delay_sec`,`retry_codes_json`,`max_visits`,`audit_level`,`created_at`)
SELECT UNHEX(MD5(CONCAT(n.workflow_code,':STEP:',s.step_code))),n.workflow_id,
 UNHEX(MD5(CONCAT(n.workflow_code,':TASK:',t.task_code))),s.step_type_code,s.step_code,s.step_name,s.sequence_no,s.description,
 s.is_enabled,s.params_json,s.input_mapping_json,s.timeout_sec,s.max_attempts,s.retry_delay_sec,s.retry_codes_json,s.max_visits,s.audit_level,UTC_TIMESTAMP(6)
FROM `_v35_navigation_source` x JOIN `Step` s ON s.workflow_id=x.workflow_id JOIN `Task` t ON t.task_id=s.task_id
JOIN `Workflow` n ON n.workflow_code IN ('WF_DYNAMIC_ENDPOINT_NAVIGATION','WF_HOSPITAL_MULTI_STOP') AND n.version_no=1
WHERE NOT EXISTS (SELECT 1 FROM `Step` e WHERE e.workflow_id=n.workflow_id AND e.step_code=s.step_code);

INSERT INTO `StepTransition` (`transition_id`,`workflow_id`,`from_step_id`,`to_step_id`,`outcome`,`condition_json`,`priority`,`is_default`,`terminal_status`)
SELECT UNHEX(MD5(CONCAT(n.workflow_code,':TRANSITION:',fs.step_code,':',st.outcome,':',st.priority))),n.workflow_id,
 UNHEX(MD5(CONCAT(n.workflow_code,':STEP:',fs.step_code))),
 IF(ts.step_code IS NULL,NULL,UNHEX(MD5(CONCAT(n.workflow_code,':STEP:',ts.step_code)))),
 st.outcome,st.condition_json,st.priority,st.is_default,st.terminal_status
FROM `_v35_navigation_source` x JOIN `StepTransition` st ON st.workflow_id=x.workflow_id
JOIN `Step` fs ON fs.step_id=st.from_step_id LEFT JOIN `Step` ts ON ts.step_id=st.to_step_id
JOIN `Workflow` n ON n.workflow_code IN ('WF_DYNAMIC_ENDPOINT_NAVIGATION','WF_HOSPITAL_MULTI_STOP') AND n.version_no=1
WHERE NOT EXISTS (SELECT 1 FROM `StepTransition` e WHERE e.workflow_id=n.workflow_id
 AND e.from_step_id=UNHEX(MD5(CONCAT(n.workflow_code,':STEP:',fs.step_code)))
 AND e.outcome=st.outcome AND e.priority=st.priority);

UPDATE `Workflow` n
JOIN `_v35_navigation_source` x
JOIN `Workflow` s ON s.workflow_id=x.workflow_id
JOIN `Step` ss ON ss.step_id=s.start_step_id
SET n.start_step_id=UNHEX(MD5(CONCAT(n.workflow_code,':STEP:',ss.step_code)))
WHERE n.workflow_code IN ('WF_DYNAMIC_ENDPOINT_NAVIGATION','WF_HOSPITAL_MULTI_STOP') AND n.version_no=1;

DROP TEMPORARY TABLE `_v35_navigation_source`;
