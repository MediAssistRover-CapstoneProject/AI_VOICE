CREATE TABLE `ShedLock` (
    `name` VARCHAR(64) NOT NULL,
    `lock_until` DATETIME(6) NOT NULL,
    `locked_at` DATETIME(6) NOT NULL,
    `locked_by` VARCHAR(255) NOT NULL,
    PRIMARY KEY (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `RetentionArchive` (
    `retention_archive_id` BINARY(16) NOT NULL,
    `dataset_name` VARCHAR(80) NOT NULL,
    `source_table` VARCHAR(80) NOT NULL,
    `source_record_id` BINARY(16) NOT NULL,
    `source_timestamp` DATETIME(6) NOT NULL,
    `payload_json` JSON NOT NULL,
    `archived_at` DATETIME(6) NOT NULL,
    `archive_version` INT NOT NULL DEFAULT 1,
    PRIMARY KEY (`retention_archive_id`),
    CONSTRAINT `UQ_RetentionArchive_source` UNIQUE (`dataset_name`, `source_record_id`),
    INDEX `IX_RetentionArchive_dataset_time` (`dataset_name`, `source_timestamp`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `SystemSetting`
(`system_setting_id`, `updated_by_account_id`, `setting_key`, `setting_value_json`, `data_type`, `category`, `description`, `is_public`, `updated_at`)
VALUES
(UUID_TO_BIN(UUID()), NULL, 'retention.execution_enabled', JSON_OBJECT('value', FALSE), 'BOOLEAN', 'RETENTION', 'Safety switch for destructive retention execution', FALSE, UTC_TIMESTAMP(6)),
(UUID_TO_BIN(UUID()), NULL, 'retention.default_mode', JSON_OBJECT('value', 'DISABLED'), 'STRING', 'RETENTION', 'Default mode: DISABLED, DELETE, or ARCHIVE', FALSE, UTC_TIMESTAMP(6)),
(UUID_TO_BIN(UUID()), NULL, 'retention.batch_size', JSON_OBJECT('value', 200), 'NUMBER', 'RETENTION', 'Maximum rows processed per retention batch', FALSE, UTC_TIMESTAMP(6)),
(UUID_TO_BIN(UUID()), NULL, 'retention.max_batches_per_run', JSON_OBJECT('value', 10), 'NUMBER', 'RETENTION', 'Maximum batches processed for each dataset in one run', FALSE, UTC_TIMESTAMP(6));
