ALTER TABLE `JobEvent`
    ADD COLUMN `consumed_by_job_step_id` BINARY(16) NULL AFTER `actor_account_id`,
    ADD COLUMN `consumed_at` DATETIME(6) NULL AFTER `persisted_at`,
    ADD CONSTRAINT `fk_job_event_consumed_step` FOREIGN KEY (`consumed_by_job_step_id`) REFERENCES `JobStep` (`job_step_id`),
    ADD INDEX `ix_job_event_wait_match` (`job_id`,`event_type`,`correlation_key`,`consumed_at`,`occurred_at`);

CREATE TABLE `QrChallenge` (
    `challenge_id` BINARY(16) NOT NULL,
    `job_id` BINARY(16) NOT NULL,
    `auth_job_step_id` BINARY(16) NOT NULL,
    `used_by_account_id` BINARY(16) NULL,
    `purpose` VARCHAR(32) NOT NULL,
    `token_hash` CHAR(64) NOT NULL,
    `issued_at` DATETIME(6) NOT NULL,
    `expires_at` DATETIME(6) NOT NULL,
    `used_at` DATETIME(6) NULL,
    `row_version` BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT `pk_qr_challenge` PRIMARY KEY (`challenge_id`),
    CONSTRAINT `uq_qr_challenge_step` UNIQUE (`auth_job_step_id`),
    CONSTRAINT `uq_qr_challenge_hash` UNIQUE (`token_hash`),
    CONSTRAINT `fk_qr_challenge_job` FOREIGN KEY (`job_id`) REFERENCES `Job` (`job_id`),
    CONSTRAINT `fk_qr_challenge_step` FOREIGN KEY (`auth_job_step_id`) REFERENCES `JobStep` (`job_step_id`),
    CONSTRAINT `fk_qr_challenge_actor` FOREIGN KEY (`used_by_account_id`) REFERENCES `Account` (`account_id`),
    CONSTRAINT `ck_qr_challenge_purpose` CHECK (`purpose` IN ('DELIVERY_SENDER','DELIVERY_RECEIVER')),
    CONSTRAINT `ck_qr_challenge_window` CHECK (`expires_at` > `issued_at`),
    INDEX `ix_qr_challenge_job_expiry` (`job_id`,`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE `DeliveryRequest`
    ADD COLUMN `pickup_compartment_confirmed_at` DATETIME(6) NULL AFTER `delivered_at`,
    ADD COLUMN `delivery_compartment_confirmed_at` DATETIME(6) NULL AFTER `pickup_compartment_confirmed_at`;

ALTER TABLE `EmergencyIncident` DROP CHECK `ck_incident_status`;
ALTER TABLE `EmergencyIncident`
    ADD CONSTRAINT `ck_incident_status` CHECK (`status` IN ('OPEN','ACKNOWLEDGED','IN_PROGRESS','RESOLVED','CLOSED','FALSE_ALARM'));
