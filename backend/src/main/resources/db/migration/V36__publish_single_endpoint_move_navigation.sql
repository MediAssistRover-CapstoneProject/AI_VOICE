-- Immutable, compiler-validated NAVIGATION release for the commissioned MOVE-only adapter.
-- It intentionally does not clone or update any previously published workflow.
SET @v36_workflow_id = UNHEX(REPLACE('9d7d8f73-3f47-4a19-8e24-932f0b739a61','-',''));
SET @v36_task_id = UNHEX(REPLACE('2e96784d-2b8d-45c5-927c-ae28b3c1f158','-',''));
SET @v36_step_id = UNHEX(REPLACE('7f1ad3c2-09f6-4a8e-b4d0-e28c70cde413','-',''));
SET @v36_compiled = CAST('{"workflowCode":"WF_NAVIGATION_SINGLE_ENDPOINT_MOVE","versionNo":1,"graphVersion":3,"schemaVersion":3,"startStepId":"7f1ad3c2-09f6-4a8e-b4d0-e28c70cde413","inputSchema":{"additionalProperties":true,"properties":{"endpoint_id":{"format":"uuid","type":"string"},"map_id":{"format":"uuid","type":"string"},"target_mode":{"const":"ENDPOINT","type":"string"}},"required":["map_id","target_mode","endpoint_id"],"type":"object"},"defaultInputs":{},"outputMapping":{"endpoint_id":{"$ref":"/inputs/endpoint_id"},"move_result":{"$ref":"/outputs/MOVE_SINGLE_ENDPOINT"}},"requiredCapabilities":["MOVE"],"tasks":[{"taskId":"2e96784d-2b8d-45c5-927c-ae28b3c1f158","taskCode":"SINGLE_ENDPOINT","sequenceNo":1,"config":{}}],"steps":[{"stepId":"7f1ad3c2-09f6-4a8e-b4d0-e28c70cde413","taskId":"2e96784d-2b8d-45c5-927c-ae28b3c1f158","stepCode":"MOVE_SINGLE_ENDPOINT","stepType":"MOVE","sequenceNo":1,"timeoutSec":180,"maxAttempts":1,"retryDelaySec":0,"maxVisits":1,"auditLevel":"FULL","params":{"max_speed_mps":0.05,"mode":"NORMAL"},"inputMapping":{"endpoint_id":{"$ref":"/inputs/endpoint_id"},"map_id":{"$ref":"/inputs/map_id"}},"retryCodes":[]}],"transitions":[{"transitionId":"48e8e23f-0dc0-4706-8498-b00bf6c5351b","fromStepId":"7f1ad3c2-09f6-4a8e-b4d0-e28c70cde413","terminalStatus":"FAILED","outcome":"FAILURE","priority":1,"default":true},{"transitionId":"df2c9b5f-90ae-4db3-922d-316d4f63bb8f","fromStepId":"7f1ad3c2-09f6-4a8e-b4d0-e28c70cde413","terminalStatus":"SUCCEEDED","outcome":"SUCCESS","priority":1,"default":true},{"transitionId":"b1f54814-92da-4788-aa21-a86b35c2f355","fromStepId":"7f1ad3c2-09f6-4a8e-b4d0-e28c70cde413","terminalStatus":"RECOVERY_REQUIRED","outcome":"TIMEOUT","priority":1,"default":true}]}' AS JSON);

INSERT INTO `Workflow` (`workflow_id`,`workflow_code`,`version_no`,`workflow_name`,`service_type`,`description`,
 `input_schema_json`,`input_ui_json`,`default_inputs_json`,`output_mapping_json`,`required_capabilities_json`,
 `max_duration_sec`,`max_total_visits`,`graph_version`,`schema_version`,`compiled_definition_json`,`definition_hash`,
 `status`,`accepts_new_jobs`,`published_at`,`row_version`,`created_at`,`updated_at`)
VALUES (@v36_workflow_id,'WF_NAVIGATION_SINGLE_ENDPOINT_MOVE',1,'Navigation - Single Endpoint MOVE only','NAVIGATION',
 'Published test workflow: one MOVE to the real endpoint UUID selected by the Navigation Request; no speech or simulated completion.',
 CAST('{"type":"object","required":["map_id","target_mode","endpoint_id"],"properties":{"map_id":{"type":"string","format":"uuid"},"target_mode":{"type":"string","const":"ENDPOINT"},"endpoint_id":{"type":"string","format":"uuid"}},"additionalProperties":true}' AS JSON),
 JSON_OBJECT(),JSON_OBJECT(),CAST('{"endpoint_id":{"$ref":"/inputs/endpoint_id"},"move_result":{"$ref":"/outputs/MOVE_SINGLE_ENDPOINT"}}' AS JSON),JSON_ARRAY('MOVE'),
 300,1,3,3,@v36_compiled,'b109004f2c8b959a3236c70ce720453af57a1319e458daf035a333c97f7d7ff4',
 'PUBLISHED',TRUE,UTC_TIMESTAMP(6),0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO `Task` (`task_id`,`workflow_id`,`task_code`,`task_name`,`sequence_no`,`description`,`config_json`,`created_at`)
VALUES (@v36_task_id,@v36_workflow_id,'SINGLE_ENDPOINT','Single endpoint navigation',1,
 'Dispatch exactly one MOVE using the request map and endpoint UUID.',JSON_OBJECT(),UTC_TIMESTAMP(6));

INSERT INTO `Step` (`step_id`,`workflow_id`,`task_id`,`step_type_code`,`step_code`,`step_name`,`sequence_no`,`description`,
 `is_enabled`,`params_json`,`input_mapping_json`,`timeout_sec`,`max_attempts`,`retry_delay_sec`,`retry_codes_json`,`max_visits`,`audit_level`,`created_at`)
VALUES (@v36_step_id,@v36_workflow_id,@v36_task_id,'MOVE','MOVE_SINGLE_ENDPOINT','Move to selected endpoint',1,
 'The endpoint_id is resolved from the validated Navigation Request, never from free-form data.',TRUE,
 CAST('{"mode":"NORMAL","max_speed_mps":0.05}' AS JSON),
 CAST('{"map_id":{"$ref":"/inputs/map_id"},"endpoint_id":{"$ref":"/inputs/endpoint_id"}}' AS JSON),
 180,1,0,JSON_ARRAY(),1,'FULL',UTC_TIMESTAMP(6));

INSERT INTO `StepTransition` (`transition_id`,`workflow_id`,`from_step_id`,`to_step_id`,`outcome`,`condition_json`,`priority`,`is_default`,`terminal_status`) VALUES
(UNHEX(REPLACE('df2c9b5f-90ae-4db3-922d-316d4f63bb8f','-','')),@v36_workflow_id,@v36_step_id,NULL,'SUCCESS',NULL,1,TRUE,'SUCCEEDED'),
(UNHEX(REPLACE('48e8e23f-0dc0-4706-8498-b00bf6c5351b','-','')),@v36_workflow_id,@v36_step_id,NULL,'FAILURE',NULL,1,TRUE,'FAILED'),
(UNHEX(REPLACE('b1f54814-92da-4788-aa21-a86b35c2f355','-','')),@v36_workflow_id,@v36_step_id,NULL,'TIMEOUT',NULL,1,TRUE,'RECOVERY_REQUIRED');

UPDATE `Workflow` SET `start_step_id`=@v36_step_id WHERE `workflow_id`=@v36_workflow_id;
