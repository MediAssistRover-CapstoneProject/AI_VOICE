-- Contract hardening after the Team Pi pre-publish review. Keep every seed DRAFT.
ALTER TABLE `EmergencyIncident`
    ADD CONSTRAINT `uk_emergency_incident_detection_event` UNIQUE (`detection_event_id`);

UPDATE `Workflow` SET
    `input_schema_json`=CAST('{"type":"object","properties":{"map_id":{"type":"string","format":"uuid"},"endpoint_id":{"type":"string","format":"uuid"},"patient_reference":{"type":["string","null"]}},"required":["map_id","endpoint_id"],"additionalProperties":true}' AS JSON),
    `required_capabilities_json`=JSON_ARRAY('MOVE','INTERACT','CHECK_CONDITION')
WHERE `workflow_code`='WF_PATIENT_ESCORT' AND `status`='DRAFT';

UPDATE `Workflow` SET
    `input_schema_json`=CAST('{"type":"object","properties":{"question":{"type":"string"}},"required":["question"],"additionalProperties":true}' AS JSON),
    `required_capabilities_json`=JSON_ARRAY('INTERACT')
WHERE `workflow_code`='WF_INFORMATION_ASSISTANCE' AND `status`='DRAFT';

UPDATE `Workflow` SET
    `input_schema_json`=CAST('{"type":"object","properties":{"map_id":{"type":"string","format":"uuid"},"pickup_endpoint_id":{"type":"string","format":"uuid"},"delivery_endpoint_id":{"type":"string","format":"uuid"},"service_request_id":{"type":"string","format":"uuid"},"compartment_id":{"type":"string"}},"required":["map_id","pickup_endpoint_id","delivery_endpoint_id","service_request_id","compartment_id"],"additionalProperties":true}' AS JSON),
    `required_capabilities_json`=JSON_ARRAY('MOVE','INTERACT','CONTROL_HARDWARE','CHECK_CONDITION')
WHERE `workflow_code`='WF_SECURE_MEDICAL_DELIVERY' AND `status`='DRAFT';

UPDATE `Workflow` SET
    `input_schema_json`=CAST('{"type":"object","properties":{"incident_id":{"type":"string","format":"uuid"},"message":{"type":"string"}},"required":["incident_id","message"],"additionalProperties":true}' AS JSON),
    `required_capabilities_json`=JSON_ARRAY('INTERACT','CHECK_CONDITION','WAIT_EVENT')
WHERE `workflow_code`='WF_EMERGENCY_SOS_RESPONSE' AND `status`='DRAFT';

UPDATE `Workflow` SET
    `input_schema_json`=CAST('{"type":"object","properties":{"map_id":{"type":"string","format":"uuid"},"checkpoint_id":{"type":"string","format":"uuid"},"zone_id":{"type":"string","format":"uuid"},"warning_count":{"type":"integer","minimum":1},"max_loops":{"type":"integer","minimum":1}},"required":["map_id","checkpoint_id","zone_id"],"additionalProperties":true}' AS JSON),
    `required_capabilities_json`=JSON_ARRAY('MOVE','CROWD_DENSITY','CHECK_CONDITION','WAIT_DURATION')
WHERE `workflow_code`='WF_CROWD_MONITORING' AND `status`='DRAFT';

UPDATE `Workflow` SET
    `input_schema_json`=CAST('{"type":"object","properties":{"detection_event_id":{"type":"string","format":"uuid"},"confidence":{"type":"number","minimum":0,"maximum":1},"min_confidence":{"type":"number","minimum":0,"maximum":1}},"required":["detection_event_id","confidence"],"additionalProperties":true}' AS JSON),
    `required_capabilities_json`=JSON_ARRAY('INTERACT','CHECK_CONDITION','WAIT_EVENT')
WHERE `workflow_code`='WF_FALL_RESPONSE' AND `status`='DRAFT';

UPDATE `Workflow` SET
    `input_schema_json`=CAST('{"type":"object","properties":{"map_id":{"type":"string","format":"uuid"},"checkpoint_id":{"type":"string","format":"uuid"},"zone_id":{"type":"string","format":"uuid"}},"required":["map_id","checkpoint_id","zone_id"],"additionalProperties":true}' AS JSON),
    `required_capabilities_json`=JSON_ARRAY('MOVE','RESTRICTED_ZONE','INTERACT','CHECK_CONDITION')
WHERE `workflow_code`='WF_RESTRICTED_MONITORING' AND `status`='DRAFT';

UPDATE `Workflow` SET
    `input_schema_json`=CAST('{"type":"object","properties":{"map_id":{"type":"string","format":"uuid"},"target_mode":{"type":"string","enum":["ENDPOINT","ENDPOINT_GROUP"]},"endpoint_id":{"type":"string","format":"uuid"},"endpoint_group_id":{"type":"string","format":"uuid"}},"required":["map_id","target_mode"],"oneOf":[{"required":["endpoint_id"]},{"required":["endpoint_group_id"]}],"additionalProperties":true}' AS JSON),
    `required_capabilities_json`=JSON_ARRAY('MOVE','INTERACT','CHECK_CONDITION')
WHERE `workflow_code`='WF_NAVIGATION' AND `status`='DRAFT';

-- Patient escort.
UPDATE `Step` s JOIN `Workflow` w ON w.`workflow_id`=s.`workflow_id` SET
 s.`params_json`=JSON_OBJECT('prompt_key','CONFIRM_ESCORT','action','CAPTURE_INPUT'),
 s.`input_mapping_json`=JSON_OBJECT('patient_reference',JSON_OBJECT('$ref','/inputs/patient_reference'),'endpoint_id',JSON_OBJECT('$ref','/inputs/endpoint_id'))
WHERE w.`workflow_code`='WF_PATIENT_ESCORT' AND w.`status`='DRAFT' AND s.`step_code`='CONFIRM';
UPDATE `Step` s JOIN `Workflow` w ON w.`workflow_id`=s.`workflow_id` SET s.`params_json`=CAST('{"expression":{"path":"/outputs/CONFIRM/confirmed","operator":"EQ","value":true}}' AS JSON) WHERE w.`workflow_code`='WF_PATIENT_ESCORT' AND w.`status`='DRAFT' AND s.`step_code`='DECIDE';
UPDATE `Step` s JOIN `Workflow` w ON w.`workflow_id`=s.`workflow_id` SET s.`input_mapping_json`=CAST('{"map_id":{"$ref":"/inputs/map_id"},"endpoint_id":{"$ref":"/inputs/endpoint_id"}}' AS JSON) WHERE w.`workflow_code`='WF_PATIENT_ESCORT' AND w.`status`='DRAFT' AND s.`step_code`='MOVE';
UPDATE `Step` s JOIN `Workflow` w ON w.`workflow_id`=s.`workflow_id` SET s.`params_json`=JSON_OBJECT('prompt_key','ESCORT_ARRIVED','action','DISPLAY') WHERE w.`workflow_code`='WF_PATIENT_ESCORT' AND w.`status`='DRAFT' AND s.`step_code`='ARRIVED';
UPDATE `Step` s JOIN `Workflow` w ON w.`workflow_id`=s.`workflow_id` SET s.`params_json`=JSON_OBJECT('notification_type','ESCORT_HELP','recipients',JSON_ARRAY()) WHERE w.`workflow_code`='WF_PATIENT_ESCORT' AND w.`status`='DRAFT' AND s.`step_code`='HELP';

-- Information assistance.
UPDATE `Step` s JOIN `Workflow` w ON w.`workflow_id`=s.`workflow_id` SET s.`params_json`=JSON_OBJECT('service_key','QUERY_HOSPITAL_INFORMATION'),s.`input_mapping_json`=CAST('{"question":{"$ref":"/inputs/question"}}' AS JSON) WHERE w.`workflow_code`='WF_INFORMATION_ASSISTANCE' AND w.`status`='DRAFT' AND s.`step_code`='QUERY';
UPDATE `Step` s JOIN `Workflow` w ON w.`workflow_id`=s.`workflow_id` SET s.`params_json`=JSON_OBJECT('prompt_key','INFORMATION_ANSWER','action','DISPLAY'),s.`input_mapping_json`=CAST('{"question":{"$ref":"/inputs/question"},"items":{"$ref":"/outputs/QUERY/data/items"}}' AS JSON) WHERE w.`workflow_code`='WF_INFORMATION_ASSISTANCE' AND w.`status`='DRAFT' AND s.`step_code`='ANSWER';
UPDATE `Step` s JOIN `Workflow` w ON w.`workflow_id`=s.`workflow_id` SET s.`params_json`=JSON_OBJECT('notification_type','INFORMATION_HELP','recipients',JSON_ARRAY()) WHERE w.`workflow_code`='WF_INFORMATION_ASSISTANCE' AND w.`status`='DRAFT' AND s.`step_code`='HELP';

-- Secure delivery: explicit destination, verification, payload and compartment contracts.
UPDATE `Step` s JOIN `Workflow` w ON w.`workflow_id`=s.`workflow_id` SET s.`input_mapping_json`=CASE s.`step_code`
 WHEN 'PICKUP' THEN CAST('{"map_id":{"$ref":"/inputs/map_id"},"endpoint_id":{"$ref":"/inputs/pickup_endpoint_id"}}' AS JSON)
 WHEN 'TRANSIT' THEN CAST('{"map_id":{"$ref":"/inputs/map_id"},"endpoint_id":{"$ref":"/inputs/delivery_endpoint_id"}}' AS JSON)
 ELSE s.`input_mapping_json` END
WHERE w.`workflow_code`='WF_SECURE_MEDICAL_DELIVERY' AND w.`status`='DRAFT' AND s.`step_code` IN ('PICKUP','TRANSIT');
UPDATE `Step` s JOIN `Workflow` w ON w.`workflow_id`=s.`workflow_id` SET
 s.`params_json`=JSON_OBJECT('purpose',IF(s.`step_code`='AUTH_SENDER','DELIVERY_SENDER','DELIVERY_RECEIVER')),
 s.`input_mapping_json`=CAST('{"service_request_id":{"$ref":"/inputs/service_request_id"}}' AS JSON)
WHERE w.`workflow_code`='WF_SECURE_MEDICAL_DELIVERY' AND w.`status`='DRAFT' AND s.`step_code` IN ('AUTH_SENDER','AUTH_RECEIVER');
UPDATE `Step` s JOIN `Workflow` w ON w.`workflow_id`=s.`workflow_id` SET s.`params_json`=CAST(CONCAT('{"expression":{"path":"/outputs/',IF(s.`step_code`='CHECK_AUTH_SENDER','AUTH_SENDER','AUTH_RECEIVER'),'/verified","operator":"EQ","value":true}}') AS JSON) WHERE w.`workflow_code`='WF_SECURE_MEDICAL_DELIVERY' AND w.`status`='DRAFT' AND s.`step_code` IN ('CHECK_AUTH_SENDER','CHECK_AUTH_RECEIVER');
UPDATE `Step` s JOIN `Workflow` w ON w.`workflow_id`=s.`workflow_id` SET
 s.`params_json`=JSON_OBJECT('action',IF(s.`step_code` LIKE 'UNLOCK%','UNLOCK_COMPARTMENT','LOCK_COMPARTMENT')),
 s.`input_mapping_json`=CAST('{"compartment_id":{"$ref":"/inputs/compartment_id"},"service_request_id":{"$ref":"/inputs/service_request_id"}}' AS JSON)
WHERE w.`workflow_code`='WF_SECURE_MEDICAL_DELIVERY' AND w.`status`='DRAFT' AND s.`step_type_code`='CONTROL_HARDWARE';
UPDATE `Step` s JOIN `Workflow` w ON w.`workflow_id`=s.`workflow_id` SET s.`input_mapping_json`=CAST('{"service_request_id":{"$ref":"/inputs/service_request_id"},"compartment_id":{"$ref":"/inputs/compartment_id"}}' AS JSON) WHERE w.`workflow_code`='WF_SECURE_MEDICAL_DELIVERY' AND w.`status`='DRAFT' AND s.`step_type_code`='HANDLE_PAYLOAD';
UPDATE `Step` s JOIN `Workflow` w ON w.`workflow_id`=s.`workflow_id` SET s.`params_json`=CAST(CONCAT('{"expression":{"path":"/outputs/',IF(s.`step_code`='CHECK_PAYLOAD_SENDER','PAYLOAD_SENDER','PAYLOAD_RECEIVER'),'/confirmed","operator":"EQ","value":true}}') AS JSON) WHERE w.`workflow_code`='WF_SECURE_MEDICAL_DELIVERY' AND w.`status`='DRAFT' AND s.`step_code` IN ('CHECK_PAYLOAD_SENDER','CHECK_PAYLOAD_RECEIVER');
UPDATE `Step` s JOIN `Workflow` w ON w.`workflow_id`=s.`workflow_id` SET s.`params_json`=JSON_OBJECT('prompt_key',IF(s.`step_code` LIKE 'CLOSE%','CONFIRM_COMPARTMENT_CLOSED','VERIFY_COMPARTMENT_LOCKED'),'action','CAPTURE_INPUT'),s.`input_mapping_json`=CAST('{"compartment_id":{"$ref":"/inputs/compartment_id"}}' AS JSON) WHERE w.`workflow_code`='WF_SECURE_MEDICAL_DELIVERY' AND w.`status`='DRAFT' AND s.`step_code` IN ('CLOSE_SENDER','VERIFY_SENDER','CLOSE_RECEIVER','VERIFY_RECEIVER');
UPDATE `Step` s JOIN `Workflow` w ON w.`workflow_id`=s.`workflow_id` SET s.`params_json`=CAST(CONCAT('{"expression":{"path":"/outputs/',CASE s.`step_code` WHEN 'CHECK_CLOSE_SENDER' THEN 'CLOSE_SENDER/closed' WHEN 'CHECK_LOCK_SENDER' THEN 'VERIFY_SENDER/locked' WHEN 'CHECK_CLOSE_RECEIVER' THEN 'CLOSE_RECEIVER/closed' ELSE 'VERIFY_RECEIVER/locked' END,'","operator":"EQ","value":true}}') AS JSON) WHERE w.`workflow_code`='WF_SECURE_MEDICAL_DELIVERY' AND w.`status`='DRAFT' AND s.`step_code` IN ('CHECK_CLOSE_SENDER','CHECK_LOCK_SENDER','CHECK_CLOSE_RECEIVER','CHECK_LOCK_RECEIVER');
UPDATE `Step` s JOIN `Workflow` w ON w.`workflow_id`=s.`workflow_id` SET s.`params_json`=JSON_OBJECT('notification_type',s.`step_code`,'recipients',JSON_ARRAY()) WHERE w.`workflow_code`='WF_SECURE_MEDICAL_DELIVERY' AND w.`status`='DRAFT' AND s.`step_type_code`='NOTIFY';

-- The remaining seeds already carry mappings; make interaction action explicit.
UPDATE `Step` s JOIN `Workflow` w ON w.`workflow_id`=s.`workflow_id` SET s.`params_json`=JSON_SET(s.`params_json`,'$.action',IF(s.`step_code` IN ('REMOTE_REVIEW','STAFF_REVIEW'),'CAPTURE_INPUT','DISPLAY')) WHERE w.`workflow_code` IN ('WF_EMERGENCY_SOS_RESPONSE','WF_FALL_RESPONSE','WF_RESTRICTED_MONITORING','WF_NAVIGATION') AND w.`status`='DRAFT' AND s.`step_type_code`='INTERACT';
