CREATE TABLE `MappingSession` (
 `session_id` BINARY(16) NOT NULL, `job_id` BINARY(16) NOT NULL, `robot_id` BINARY(16) NOT NULL,
 `artifact_id` BINARY(16) NOT NULL, `state` VARCHAR(32) NOT NULL, `potentially_open` BOOLEAN NOT NULL,
 `cleanup_terminal` VARCHAR(24) NULL, `created_at` DATETIME(6) NOT NULL, `closed_at` DATETIME(6) NULL,
 PRIMARY KEY (`session_id`), UNIQUE KEY `uq_mapping_job` (`job_id`), UNIQUE KEY `uq_mapping_artifact` (`artifact_id`),
 KEY `ix_mapping_open_robot` (`robot_id`,`potentially_open`),
 CONSTRAINT `fk_mapping_job` FOREIGN KEY (`job_id`) REFERENCES `Job` (`job_id`),
 CONSTRAINT `fk_mapping_robot` FOREIGN KEY (`robot_id`) REFERENCES `Robot` (`robot_id`)
) ENGINE=InnoDB;
CREATE TABLE `MapArtifact` (
 `artifact_id` BINARY(16) NOT NULL, `session_id` BINARY(16) NOT NULL,
 `archive_hash` CHAR(64) NOT NULL, `asset_hash` CHAR(64) NOT NULL, `archive_size` BIGINT NOT NULL,
 `manifest_json` JSON NOT NULL, `map_id` BINARY(16) NULL, `stored_at` DATETIME(6) NOT NULL,
 PRIMARY KEY (`artifact_id`), UNIQUE KEY `uq_artifact_session` (`session_id`), UNIQUE KEY `uq_artifact_map` (`map_id`),
 CONSTRAINT `fk_artifact_session` FOREIGN KEY (`session_id`) REFERENCES `MappingSession` (`session_id`),
 CONSTRAINT `fk_artifact_map` FOREIGN KEY (`map_id`) REFERENCES `Map` (`map_id`),
 CONSTRAINT `ck_artifact_size` CHECK (`archive_size` > 0)
) ENGINE=InnoDB;
ALTER TABLE `JobStep` ADD COLUMN `mapping_cleanup` BOOLEAN NOT NULL DEFAULT FALSE;
