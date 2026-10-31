-- Only migration-owned drafts; historical/published/admin-authored graphs are preserved.
CREATE TEMPORARY TABLE `_phase13_drafts` AS
 SELECT workflow_id FROM `Workflow` WHERE schema_version=3 AND status='DRAFT'
 AND workflow_code IN ('WF_CROWD_MONITORING','WF_RESTRICTED_AREA_MONITORING','WF_FALL_DETECTION_RESPONSE','WF_EMERGENCY_SOS_RESPONSE','WF_INFORMATION_ASSISTANCE','WF_PATIENT_ESCORT')
 AND description LIKE '[MAR-STEP-2% DRAFT] Cloned schema 2 version %';

-- Wait for a fresh producer sample rather than assuming a cached count is a time-window average.
UPDATE `Step` s JOIN `_phase13_drafts` d ON d.workflow_id=s.workflow_id
SET s.params_json=JSON_SET(s.params_json,'$.window_sec',3),s.max_attempts=1
WHERE s.step_type_code='EXECUTE_SENSING' AND JSON_UNQUOTE(JSON_EXTRACT(s.params_json,'$.sensing_type')) IN ('CROWD_DENSITY','RESTRICTED_ZONE');

-- A staff review must produce an explicit confirmation, not merely acknowledge a DISPLAY.
UPDATE `Step` s JOIN `Workflow` w ON w.workflow_id=s.workflow_id JOIN `_phase13_drafts` d ON d.workflow_id=w.workflow_id
SET s.params_json=JSON_SET(s.params_json,'$.action','CONFIRM','$.required_role','STAFF','$.actor','STAFF')
WHERE w.workflow_code='WF_FALL_DETECTION_RESPONSE' AND s.step_code='STAFF_REVIEW';
UPDATE `Step` s JOIN `Workflow` w ON w.workflow_id=s.workflow_id JOIN `_phase13_drafts` d ON d.workflow_id=w.workflow_id
SET s.params_json=JSON_SET(s.params_json,'$.required_role','STAFF','$.actor','STAFF')
WHERE w.workflow_code='WF_EMERGENCY_SOS_RESPONSE' AND s.step_code IN ('REMOTE_REVIEW','CONFIRM_DISPATCH');

-- UI receives the actual answer/source returned by the existing hospital query service.
UPDATE `Step` s JOIN `Workflow` w ON w.workflow_id=s.workflow_id JOIN `_phase13_drafts` d ON d.workflow_id=w.workflow_id
SET s.input_mapping_json=JSON_SET(s.input_mapping_json,'$.data.answer',JSON_OBJECT('$ref','/outputs/QUERY/data/answer'),
 '$.data.source',JSON_OBJECT('$ref','/outputs/QUERY/data/source'),'$.data.found',JSON_OBJECT('$ref','/outputs/QUERY/data/found'))
WHERE w.workflow_code='WF_INFORMATION_ASSISTANCE' AND s.step_code='ANSWER';
UPDATE `Step` s JOIN `Workflow` w ON w.workflow_id=s.workflow_id JOIN `_phase13_drafts` d ON d.workflow_id=w.workflow_id
SET s.params_json=JSON_SET(s.params_json,'$.mode','ESCORT')
WHERE w.workflow_code='WF_PATIENT_ESCORT' AND s.step_code='MOVE';

UPDATE `Workflow` w JOIN `_phase13_drafts` d ON d.workflow_id=w.workflow_id
SET w.compiled_definition_json=NULL,w.definition_hash=NULL,
 w.description=REPLACE(w.description,'[MAR-STEP-2 COMPILED DRAFT]','[MAR-STEP-2 DRAFT]');
DROP TEMPORARY TABLE `_phase13_drafts`;
