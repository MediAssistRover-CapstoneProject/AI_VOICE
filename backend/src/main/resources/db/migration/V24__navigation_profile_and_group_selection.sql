CREATE TABLE `RobotNavigationProfile` (
 `robot_id` BINARY(16) NOT NULL, `map_id` BINARY(16) NOT NULL,
 `transport_session_id` BINARY(16) NOT NULL, `producer_session_id` BINARY(16) NOT NULL,
 `device_token_version` BIGINT NOT NULL, `profile_hash` CHAR(64) NOT NULL, `map_asset_hash` CHAR(64) NOT NULL,
 `acknowledged_at` DATETIME(6) NOT NULL, PRIMARY KEY (`robot_id`),
 CONSTRAINT `fk_navigation_profile_robot` FOREIGN KEY (`robot_id`) REFERENCES `Robot` (`robot_id`),
 CONSTRAINT `fk_navigation_profile_map` FOREIGN KEY (`map_id`) REFERENCES `Map` (`map_id`)
) ENGINE=InnoDB;
CREATE TABLE `JobNavigationSelection` (
 `selection_id` BINARY(16) NOT NULL, `resolve_job_step_id` BINARY(16) NOT NULL,
 `selector_job_step_id` BINARY(16) NOT NULL, `move_job_step_id` BINARY(16) NULL,
 `endpoint_group_id` BINARY(16) NOT NULL, `endpoint_id` BINARY(16) NOT NULL,
 `target_snapshot_json` JSON NOT NULL, `target_index` INT NOT NULL, `created_at` DATETIME(6) NOT NULL,
 PRIMARY KEY (`selection_id`), UNIQUE KEY `uq_navigation_selector` (`selector_job_step_id`),
 UNIQUE KEY `uq_navigation_move` (`move_job_step_id`),
 CONSTRAINT `fk_navigation_resolve` FOREIGN KEY (`resolve_job_step_id`) REFERENCES `JobStep` (`job_step_id`),
 CONSTRAINT `fk_navigation_selector` FOREIGN KEY (`selector_job_step_id`) REFERENCES `JobStep` (`job_step_id`),
 CONSTRAINT `fk_navigation_move` FOREIGN KEY (`move_job_step_id`) REFERENCES `JobStep` (`job_step_id`),
 CONSTRAINT `fk_navigation_group` FOREIGN KEY (`endpoint_group_id`) REFERENCES `EndpointGroup` (`endpoint_group_id`),
 CONSTRAINT `fk_navigation_endpoint` FOREIGN KEY (`endpoint_id`) REFERENCES `Endpoint` (`endpoint_id`),
 CONSTRAINT `ck_navigation_index` CHECK (`target_index` >= 0)
) ENGINE=InnoDB;
ALTER TABLE `RobotCommandOutbox` ADD COLUMN `navigation_profile_hash` CHAR(64) NULL;

-- Only migration-owned schema 3 drafts. An aborted navigation is not evidence of an unavailable path.
UPDATE `StepTransition` t JOIN `Step` s ON s.step_id=t.from_step_id JOIN `Workflow` w ON w.workflow_id=s.workflow_id
JOIN `Step` h ON h.workflow_id=w.workflow_id AND h.step_code='HELP'
SET t.priority=2,t.to_step_id=h.step_id
WHERE w.workflow_code='WF_NAVIGATION' AND w.schema_version=3 AND w.status='DRAFT'
AND w.description LIKE '[MAR-STEP-2% DRAFT] Cloned schema 2 version %'
AND s.step_code='NAVIGATE_GROUP' AND t.outcome='FAILURE' AND t.is_default=TRUE;

UPDATE `StepTransition` t JOIN `Step` s ON s.step_id=t.from_step_id
JOIN `Workflow` w ON w.workflow_id=s.workflow_id JOIN `Step` n ON n.workflow_id=w.workflow_id AND n.step_code='NEXT_TARGET'
SET t.to_step_id=n.step_id,t.priority=1,t.condition_json=JSON_OBJECT('path','/error/errorCode','operator','EQ','value','PATH_UNAVAILABLE')
WHERE w.workflow_code='WF_NAVIGATION' AND w.schema_version=3 AND w.status='DRAFT'
AND w.description LIKE '[MAR-STEP-2% DRAFT] Cloned schema 2 version %'
AND s.step_code='NAVIGATE_GROUP' AND t.outcome='FAILURE' AND t.is_default=FALSE;

UPDATE `Workflow` w SET w.compiled_definition_json=NULL,w.definition_hash=NULL,
 w.description=REPLACE(w.description,'[MAR-STEP-2 COMPILED DRAFT]','[MAR-STEP-2 DRAFT]')
WHERE w.workflow_code='WF_NAVIGATION' AND w.schema_version=3 AND w.status='DRAFT'
AND w.description LIKE '[MAR-STEP-2% DRAFT] Cloned schema 2 version %';
