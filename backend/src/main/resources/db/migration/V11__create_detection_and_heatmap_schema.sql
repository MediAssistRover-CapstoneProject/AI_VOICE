CREATE TABLE `DetectionEvent` (
    `detection_event_id` BINARY(16) NOT NULL,
    `robot_id` BINARY(16) NOT NULL,
    `map_id` BINARY(16) NOT NULL,
    `zone_id` BINARY(16) NULL,
    `event_type` VARCHAR(40) NOT NULL,
    `confidence_score` DECIMAL(5,4) NULL,
    `x_m` DECIMAL(12,4) NULL,
    `y_m` DECIMAL(12,4) NULL,
    `location_basis` VARCHAR(16) NOT NULL,
    `model_version` VARCHAR(100) NULL,
    `event_payload_json` JSON NOT NULL,
    `evidence_ref` TEXT NULL,
    `detected_at` DATETIME(6) NOT NULL,
    `received_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`detection_event_id`),
    CONSTRAINT `FK_DetectionEvent_robot` FOREIGN KEY (`robot_id`) REFERENCES `Robot` (`robot_id`),
    CONSTRAINT `FK_DetectionEvent_map` FOREIGN KEY (`map_id`) REFERENCES `Map` (`map_id`),
    CONSTRAINT `FK_DetectionEvent_zone` FOREIGN KEY (`zone_id`) REFERENCES `Zone` (`zone_id`),
    CONSTRAINT `CK_DetectionEvent_type` CHECK (`event_type` IN ('SUSPECTED_FALL','RESTRICTED_INTRUSION','SOS_SIGNAL','OTHER')),
    CONSTRAINT `CK_DetectionEvent_confidence` CHECK (`confidence_score` IS NULL OR (`confidence_score` >= 0 AND `confidence_score` <= 1)),
    CONSTRAINT `CK_DetectionEvent_coordinates` CHECK ((`x_m` IS NULL AND `y_m` IS NULL) OR (`x_m` IS NOT NULL AND `y_m` IS NOT NULL)),
    CONSTRAINT `CK_DetectionEvent_location_basis` CHECK (`location_basis` IN ('OBJECT','ROBOT','UNKNOWN')),
    INDEX `IX_DetectionEvent_robot_time` (`robot_id`, `detected_at`),
    INDEX `IX_DetectionEvent_type_time` (`event_type`, `detected_at`),
    INDEX `IX_DetectionEvent_zone_time` (`zone_id`, `detected_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `CrowdDensityRecord` (
    `crowd_density_record_id` BINARY(16) NOT NULL,
    `robot_id` BINARY(16) NOT NULL,
    `zone_id` BINARY(16) NOT NULL,
    `checkpoint_id` BINARY(16) NULL,
    `job_step_id` BINARY(16) NULL,
    `person_count` INT NOT NULL,
    `density_level` VARCHAR(16) NULL,
    `confidence_score` DECIMAL(5,4) NULL,
    `model_version` VARCHAR(100) NULL,
    `metrics_json` JSON NOT NULL,
    `evidence_ref` TEXT NULL,
    `captured_at` DATETIME(6) NOT NULL,
    `recorded_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`crowd_density_record_id`),
    CONSTRAINT `FK_CrowdDensityRecord_robot` FOREIGN KEY (`robot_id`) REFERENCES `Robot` (`robot_id`),
    CONSTRAINT `FK_CrowdDensityRecord_zone` FOREIGN KEY (`zone_id`) REFERENCES `Zone` (`zone_id`),
    CONSTRAINT `FK_CrowdDensityRecord_checkpoint` FOREIGN KEY (`checkpoint_id`) REFERENCES `Endpoint` (`endpoint_id`),
    CONSTRAINT `FK_CrowdDensityRecord_job_step` FOREIGN KEY (`job_step_id`) REFERENCES `JobStep` (`job_step_id`),
    CONSTRAINT `CK_CrowdDensityRecord_count` CHECK (`person_count` >= 0),
    CONSTRAINT `CK_CrowdDensityRecord_level` CHECK (`density_level` IS NULL OR `density_level` IN ('LOW','MEDIUM','HIGH')),
    CONSTRAINT `CK_CrowdDensityRecord_confidence` CHECK (`confidence_score` IS NULL OR (`confidence_score` >= 0 AND `confidence_score` <= 1)),
    INDEX `IX_CrowdDensityRecord_zone_time` (`zone_id`, `captured_at`),
    INDEX `IX_CrowdDensityRecord_robot_time` (`robot_id`, `captured_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `HeatmapRecord` (
    `heatmap_record_id` BINARY(16) NOT NULL,
    `map_id` BINARY(16) NOT NULL,
    `heatmap_type` VARCHAR(64) NOT NULL,
    `period_start` DATETIME(6) NOT NULL,
    `period_end` DATETIME(6) NOT NULL,
    `cell_size_m` DECIMAL(10,4) NOT NULL,
    `grid_data_json` JSON NOT NULL,
    `image_uri` TEXT NULL,
    `max_value` DECIMAL(14,4) NULL,
    `source_record_count` INT NOT NULL,
    `generated_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`heatmap_record_id`),
    CONSTRAINT `UQ_HeatmapRecord_window` UNIQUE (`map_id`, `heatmap_type`, `period_start`, `period_end`, `cell_size_m`),
    CONSTRAINT `FK_HeatmapRecord_map` FOREIGN KEY (`map_id`) REFERENCES `Map` (`map_id`),
    CONSTRAINT `CK_HeatmapRecord_period` CHECK (`period_start` < `period_end`),
    CONSTRAINT `CK_HeatmapRecord_cell_size` CHECK (`cell_size_m` > 0),
    CONSTRAINT `CK_HeatmapRecord_source_count` CHECK (`source_record_count` >= 0),
    INDEX `IX_HeatmapRecord_map_type_time` (`map_id`, `heatmap_type`, `period_start`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE `EmergencyIncident`
    ADD CONSTRAINT `FK_EmergencyIncident_detection_event` FOREIGN KEY (`detection_event_id`) REFERENCES `DetectionEvent` (`detection_event_id`);

ALTER TABLE `Alert`
    ADD CONSTRAINT `FK_Alert_detection_event` FOREIGN KEY (`detection_event_id`) REFERENCES `DetectionEvent` (`detection_event_id`);
