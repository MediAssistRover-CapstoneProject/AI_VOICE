-- Runtime-overridable operational default. Environment/property remains the fallback.
INSERT INTO `SystemSetting` (`system_setting_id`, `updated_by_account_id`, `setting_key`, `setting_value_json`, `data_type`, `category`, `description`, `is_public`, `updated_at`)
VALUES (UUID_TO_BIN(UUID()), NULL, 'operations.robot_offline_threshold_seconds', JSON_OBJECT('value', 30), 'NUMBER', 'OPERATIONS', 'Seconds without heartbeat before a robot is marked OFFLINE', FALSE, UTC_TIMESTAMP(6));

-- Retention policy is metadata only in MVP. No scheduler deletes history automatically.
INSERT INTO `SystemSetting` (`system_setting_id`, `updated_by_account_id`, `setting_key`, `setting_value_json`, `data_type`, `category`, `description`, `is_public`, `updated_at`) VALUES
(UUID_TO_BIN(UUID()), NULL, 'retention.audit_log_days', JSON_OBJECT('value', 1095), 'NUMBER', 'RETENTION', 'AuditLog retention policy: 3 years', FALSE, UTC_TIMESTAMP(6)),
(UUID_TO_BIN(UUID()), NULL, 'retention.job_event_days', JSON_OBJECT('value', 1095), 'NUMBER', 'RETENTION', 'JobEvent retention policy: 3 years', FALSE, UTC_TIMESTAMP(6)),
(UUID_TO_BIN(UUID()), NULL, 'retention.alert_days', JSON_OBJECT('value', 1095), 'NUMBER', 'RETENTION', 'Alert retention policy: 3 years', FALSE, UTC_TIMESTAMP(6)),
(UUID_TO_BIN(UUID()), NULL, 'retention.emergency_incident_days', JSON_OBJECT('value', 1095), 'NUMBER', 'RETENTION', 'EmergencyIncident retention policy: 3 years', FALSE, UTC_TIMESTAMP(6)),
(UUID_TO_BIN(UUID()), NULL, 'retention.robot_status_history_days', JSON_OBJECT('value', 365), 'NUMBER', 'RETENTION', 'RobotStatusHistory retention policy: 1 year', FALSE, UTC_TIMESTAMP(6)),
(UUID_TO_BIN(UUID()), NULL, 'retention.robot_location_history_days', JSON_OBJECT('value', 183), 'NUMBER', 'RETENTION', 'RobotLocationHistory retention policy: 6 months', FALSE, UTC_TIMESTAMP(6)),
(UUID_TO_BIN(UUID()), NULL, 'retention.detection_event_days', JSON_OBJECT('value', 365), 'NUMBER', 'RETENTION', 'DetectionEvent retention policy: 1 year', FALSE, UTC_TIMESTAMP(6)),
(UUID_TO_BIN(UUID()), NULL, 'retention.crowd_density_record_days', JSON_OBJECT('value', 365), 'NUMBER', 'RETENTION', 'CrowdDensityRecord retention policy: 1 year', FALSE, UTC_TIMESTAMP(6)),
(UUID_TO_BIN(UUID()), NULL, 'retention.heatmap_record_days', JSON_OBJECT('value', 365), 'NUMBER', 'RETENTION', 'HeatmapRecord retention policy: 1 year', FALSE, UTC_TIMESTAMP(6));
