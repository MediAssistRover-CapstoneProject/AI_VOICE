-- Add a bounded group subgraph only to migration-owned delivery drafts. Official UNLOCK remains gated.
CREATE TEMPORARY TABLE `_delivery_group_drafts` AS
 SELECT workflow_id FROM `Workflow` WHERE workflow_code='WF_SECURE_MEDICAL_DELIVERY' AND schema_version=3
 AND status='DRAFT' AND description LIKE '[MAR-STEP-2% DRAFT] Cloned schema 2 version %';
UPDATE `Workflow` w JOIN `_delivery_group_drafts` d ON d.workflow_id=w.workflow_id
SET w.input_schema_json=JSON_SET(w.input_schema_json,
 '$.properties.target_mode',JSON_OBJECT('type','string','enum',JSON_ARRAY('ENDPOINT','ENDPOINT_GROUP')),
 '$.properties.delivery_group_id',JSON_OBJECT('type','string','format','uuid'),
 '$.required',JSON_ARRAY('map_id','pickup_endpoint_id','service_request_id','compartment_id','target_mode'),
 '$.oneOf',CAST('[{"properties":{"target_mode":{"const":"ENDPOINT"}},"required":["delivery_endpoint_id"],"not":{"required":["delivery_group_id"]}},{"properties":{"target_mode":{"const":"ENDPOINT_GROUP"}},"required":["delivery_group_id"],"not":{"required":["delivery_endpoint_id"]}}]' AS JSON)),
 w.default_inputs_json=JSON_SET(w.default_inputs_json,'$.target_mode',COALESCE(JSON_EXTRACT(w.default_inputs_json,'$.target_mode'),CAST('"ENDPOINT"' AS JSON)));
CREATE TEMPORARY TABLE `_delivery_group_steps` (
 code VARCHAR(80),kind VARCHAR(32),seq INT,params JSON,mapping JSON,timeout_sec INT
);
INSERT INTO `_delivery_group_steps` VALUES
 ('GROUP_MODE','CHECK_CONDITION',101,JSON_OBJECT('expression',JSON_OBJECT('path','/inputs/target_mode','operator','EQ','value','ENDPOINT_GROUP')),JSON_OBJECT(),30),
 ('GROUP_RESOLVE','DATA_SERVICE',102,JSON_OBJECT('service_key','RESOLVE_TARGETS'),JSON_OBJECT('endpoint_group_id',JSON_OBJECT('$ref','/inputs/delivery_group_id')),30),
 ('GROUP_INIT','SET_CONTEXT',103,CAST('{"operations":[{"path":"/vars/delivery_target_index","value":0}]}' AS JSON),JSON_OBJECT(),30),
 ('GROUP_SELECT','DATA_SERVICE',104,JSON_OBJECT('service_key','SELECT_TARGET'),JSON_OBJECT('targets',JSON_OBJECT('$ref','/outputs/GROUP_RESOLVE/data/targets'),'index',JSON_OBJECT('$ref','/vars/delivery_target_index')),30),
 ('GROUP_HAS','CHECK_CONDITION',105,JSON_OBJECT('expression',JSON_OBJECT('path','/outputs/GROUP_SELECT/data/has_target','operator','EQ','value',TRUE)),JSON_OBJECT(),30),
 ('GROUP_MOVE','MOVE',106,JSON_OBJECT('mode','NORMAL','max_speed_mps',0.05),JSON_OBJECT('map_id',JSON_OBJECT('$ref','/inputs/map_id'),'endpoint_id',JSON_OBJECT('$ref','/outputs/GROUP_SELECT/data/endpoint_id')),180),
 ('GROUP_NEXT','SET_CONTEXT',107,CAST('{"operations":[{"path":"/vars/delivery_target_index","operation":"INCREMENT","value":1}]}' AS JSON),JSON_OBJECT(),30);

INSERT INTO `Step` (`step_id`,`workflow_id`,`task_id`,`step_type_code`,`step_code`,`step_name`,`sequence_no`,`is_enabled`,
 `params_json`,`input_mapping_json`,`timeout_sec`,`max_attempts`,`retry_delay_sec`,`retry_codes_json`,`max_visits`,`audit_level`,`created_at`)
SELECT UNHEX(MD5(CONCAT(HEX(w.workflow_id),':PHASE11:',g.code))),w.workflow_id,t.task_id,g.kind,g.code,g.code,g.seq,TRUE,
 g.params,g.mapping,g.timeout_sec,1,0,JSON_ARRAY(),10,'FULL',UTC_TIMESTAMP(6)
FROM `_delivery_group_drafts` w JOIN `Step` t ON t.workflow_id=w.workflow_id AND t.step_code='TRANSIT'
CROSS JOIN `_delivery_group_steps` g;

-- Keep direct-endpoint TRANSIT and its success/failure behavior. Group mode takes its own MOVE visits.
UPDATE `StepTransition` r JOIN `Step` old ON old.step_id=r.to_step_id
JOIN `_delivery_group_drafts` w ON w.workflow_id=r.workflow_id
JOIN `Step` branch ON branch.workflow_id=w.workflow_id AND branch.step_code='GROUP_MODE'
SET r.to_step_id=branch.step_id WHERE old.step_code='TRANSIT';

CREATE TEMPORARY TABLE `_delivery_group_routes` (code VARCHAR(80),outcome VARCHAR(16),target VARCHAR(80),priority INT,default_route BOOLEAN,condition_json JSON);
INSERT INTO `_delivery_group_routes` VALUES
 ('GROUP_MODE','SUCCESS','GROUP_RESOLVE',1,FALSE,JSON_OBJECT('path','/result/value','operator','EQ','value',TRUE)),
 ('GROUP_MODE','SUCCESS','TRANSIT',2,TRUE,NULL),
 ('GROUP_RESOLVE','SUCCESS','GROUP_INIT',1,TRUE,NULL),
 ('GROUP_INIT','SUCCESS','GROUP_SELECT',1,TRUE,NULL),
 ('GROUP_SELECT','SUCCESS','GROUP_HAS',1,TRUE,NULL),
 ('GROUP_HAS','SUCCESS','GROUP_MOVE',1,FALSE,JSON_OBJECT('path','/result/value','operator','EQ','value',TRUE)),
 ('GROUP_HAS','SUCCESS','HELP',2,TRUE,NULL),
 ('GROUP_MOVE','SUCCESS','AUTH_RECEIVER',1,TRUE,NULL),
 ('GROUP_MOVE','FAILURE','GROUP_NEXT',1,FALSE,JSON_OBJECT('path','/error/errorCode','operator','EQ','value','PATH_UNAVAILABLE')),
 ('GROUP_NEXT','SUCCESS','GROUP_SELECT',1,TRUE,NULL);
INSERT INTO `_delivery_group_routes`
 SELECT code,'FAILURE','HELP',IF(code='GROUP_MOVE',2,1),TRUE,NULL FROM `_delivery_group_steps`;
INSERT INTO `_delivery_group_routes`
 SELECT code,'TIMEOUT','HELP',1,TRUE,NULL FROM `_delivery_group_steps`;

INSERT INTO `StepTransition` (`transition_id`,`workflow_id`,`from_step_id`,`to_step_id`,`outcome`,`condition_json`,`priority`,`is_default`,`terminal_status`)
SELECT UNHEX(MD5(CONCAT(HEX(w.workflow_id),':PHASE11:',r.code,':',r.outcome,':',r.priority))),w.workflow_id,s.step_id,n.step_id,
 r.outcome,r.condition_json,r.priority,r.default_route,NULL
FROM `_delivery_group_drafts` w CROSS JOIN `_delivery_group_routes` r
JOIN `Step` s ON s.workflow_id=w.workflow_id AND s.step_code=r.code
JOIN `Step` n ON n.workflow_id=w.workflow_id AND n.step_code=r.target;

UPDATE `Workflow` w JOIN `_delivery_group_drafts` d ON d.workflow_id=w.workflow_id
SET w.compiled_definition_json=NULL,w.definition_hash=NULL,
 w.description=REPLACE(w.description,'[MAR-STEP-2 COMPILED DRAFT]','[MAR-STEP-2 DRAFT]');
DROP TEMPORARY TABLE `_delivery_group_routes`;
DROP TEMPORARY TABLE `_delivery_group_steps`;
DROP TEMPORARY TABLE `_delivery_group_drafts`;
