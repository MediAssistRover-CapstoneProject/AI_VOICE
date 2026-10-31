-- Schema 2 verification consumption and delivery timestamps retain their existing meanings.
ALTER TABLE `AuthVerification` ADD COLUMN `actor_token_version` BIGINT NULL;
CREATE TABLE `RobotOperationAuthorization` (
 `authorization_id` BINARY(16) NOT NULL,
 `verification_id` BINARY(16) NOT NULL,
 `job_step_id` BINARY(16) NOT NULL,
 `robot_id` BINARY(16) NOT NULL,
 `operation_key` VARCHAR(150) NOT NULL,
 `authorization_json` JSON NOT NULL,
 `issued_at` DATETIME(6) NOT NULL,
 `valid_until` DATETIME(6) NOT NULL,
 PRIMARY KEY (`authorization_id`),
 UNIQUE KEY `uq_operation_authorization_verification` (`verification_id`),
 UNIQUE KEY `uq_operation_authorization_step` (`job_step_id`),
 UNIQUE KEY `uq_operation_authorization_key` (`operation_key`),
 CONSTRAINT `fk_operation_authorization_verification` FOREIGN KEY (`verification_id`) REFERENCES `AuthVerification` (`verification_id`),
 CONSTRAINT `fk_operation_authorization_step` FOREIGN KEY (`job_step_id`) REFERENCES `JobStep` (`job_step_id`),
 CONSTRAINT `fk_operation_authorization_robot` FOREIGN KEY (`robot_id`) REFERENCES `Robot` (`robot_id`),
 CONSTRAINT `ck_operation_authorization_expiry` CHECK (`valid_until` > `issued_at`)
) ENGINE=InnoDB;

ALTER TABLE `DeliveryRequest`
 ADD COLUMN `pickup_payload_confirmed_at` DATETIME(6) NULL,
 ADD COLUMN `delivery_payload_confirmed_at` DATETIME(6) NULL;

-- Only schema 3 drafts: authorization values come from the selected AUTHENTICATE output.
-- This does NOT enable UNLOCK: current MAR-STEP-2 Pi contract has no STAFF_VERIFIED capability/output.
UPDATE `Step` s JOIN `Workflow` w ON w.workflow_id=s.workflow_id
SET s.input_mapping_json=JSON_SET(s.input_mapping_json,'$.authorization',
 JSON_OBJECT('mode','STAFF_VERIFIED',
 'verification_id',JSON_OBJECT('$ref',CONCAT('/outputs/',IF(s.step_code='UNLOCK_SENDER','AUTH_SENDER','AUTH_RECEIVER'),'/verification_id')),
 'actor_account_id',JSON_OBJECT('$ref',CONCAT('/outputs/',IF(s.step_code='UNLOCK_SENDER','AUTH_SENDER','AUTH_RECEIVER'),'/actor_account_id')),
 'purpose',IF(s.step_code='UNLOCK_SENDER','DELIVERY_SENDER','DELIVERY_RECEIVER'),
 'valid_until',JSON_OBJECT('$ref',CONCAT('/outputs/',IF(s.step_code='UNLOCK_SENDER','AUTH_SENDER','AUTH_RECEIVER'),'/valid_until'))))
WHERE w.workflow_code='WF_SECURE_MEDICAL_DELIVERY' AND w.schema_version=3 AND w.status='DRAFT'
AND w.description LIKE '[MAR-STEP-2% DRAFT] Cloned schema 2 version %'
AND s.step_code IN ('UNLOCK_SENDER','UNLOCK_RECEIVER');

-- Recompile only migration-owned drafts; hashes for published workflows/runtime Jobs are untouched.
UPDATE `Workflow` w
SET w.compiled_definition_json=NULL,w.definition_hash=NULL,
 w.description=REPLACE(w.description,'[MAR-STEP-2 COMPILED DRAFT]','[MAR-STEP-2 DRAFT]')
WHERE w.workflow_code='WF_SECURE_MEDICAL_DELIVERY' AND w.schema_version=3 AND w.status='DRAFT'
AND w.description LIKE '[MAR-STEP-2% DRAFT] Cloned schema 2 version %';
