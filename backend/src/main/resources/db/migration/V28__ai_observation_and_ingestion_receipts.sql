CREATE TABLE `AiSensingProfile` (
 `profile_id` BINARY(16) NOT NULL, `robot_id` BINARY(16) NOT NULL, `zone_id` BINARY(16) NOT NULL,
 `sensing_type` VARCHAR(32) NOT NULL, `model_version` VARCHAR(100) NOT NULL, `producer_ref` VARCHAR(255) NOT NULL,
 `map_asset_hash` CHAR(64) NOT NULL, `frame_id` VARCHAR(64) NOT NULL, `enabled` BOOLEAN NOT NULL,
 `person_positions_enabled` BOOLEAN NOT NULL, `commissioning_note` VARCHAR(1000) NOT NULL,
 `configured_by` BINARY(16) NOT NULL, `configured_at` DATETIME(6) NOT NULL,
 PRIMARY KEY (`profile_id`), UNIQUE KEY `uq_ai_profile_scope` (`robot_id`,`zone_id`,`sensing_type`),
 CONSTRAINT `fk_ai_profile_robot` FOREIGN KEY (`robot_id`) REFERENCES `Robot` (`robot_id`),
 CONSTRAINT `fk_ai_profile_zone` FOREIGN KEY (`zone_id`) REFERENCES `Zone` (`zone_id`),
 CONSTRAINT `fk_ai_profile_actor` FOREIGN KEY (`configured_by`) REFERENCES `Account` (`account_id`),
 CONSTRAINT `ck_ai_profile_type` CHECK (`sensing_type` IN ('CROWD_DENSITY','RESTRICTED_ZONE'))
) ENGINE=InnoDB;
CREATE TABLE `AiObservation` (
 `observation_key` CHAR(64) NOT NULL, `robot_id` BINARY(16) NOT NULL, `job_step_id` BINARY(16) NOT NULL,
 `payload_hash` CHAR(64) NOT NULL, `output_json` JSON NOT NULL, `profile_snapshot_json` JSON NOT NULL,
 `crowd_record_id` BINARY(16) NULL, `detection_event_id` BINARY(16) NULL, `stored_at` DATETIME(6) NOT NULL,
 PRIMARY KEY (`observation_key`),
 CONSTRAINT `fk_ai_observation_robot` FOREIGN KEY (`robot_id`) REFERENCES `Robot` (`robot_id`),
 CONSTRAINT `fk_ai_observation_step` FOREIGN KEY (`job_step_id`) REFERENCES `JobStep` (`job_step_id`),
 CONSTRAINT `fk_ai_observation_crowd` FOREIGN KEY (`crowd_record_id`) REFERENCES `CrowdDensityRecord` (`crowd_density_record_id`),
 CONSTRAINT `fk_ai_observation_detection` FOREIGN KEY (`detection_event_id`) REFERENCES `DetectionEvent` (`detection_event_id`)
) ENGINE=InnoDB;
CREATE TABLE `MonitoringIngestionReceipt` (
 `message_id` BINARY(16) NOT NULL, `robot_id` BINARY(16) NOT NULL, `message_type` VARCHAR(40) NOT NULL,
 `payload_hash` CHAR(64) NOT NULL, `received_at` DATETIME(6) NOT NULL, `provenance_json` JSON NULL,
 PRIMARY KEY (`message_id`), KEY `ix_monitoring_receipt_robot` (`robot_id`),
 CONSTRAINT `fk_monitoring_receipt_robot` FOREIGN KEY (`robot_id`) REFERENCES `Robot` (`robot_id`)
) ENGINE=InnoDB;
ALTER TABLE `RobotCommandOutbox` ADD COLUMN `ai_profile_snapshot_json` JSON NULL;
