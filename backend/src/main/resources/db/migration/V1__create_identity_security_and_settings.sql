CREATE TABLE `Account` (
    `account_id` BINARY(16) NOT NULL,
    `account_code` VARCHAR(64) NOT NULL,
    `username` VARCHAR(100) NOT NULL,
    `full_name` VARCHAR(160) NOT NULL,
    `email` VARCHAR(254) NULL,
    `phone` VARCHAR(32) NULL,
    `account_type` VARCHAR(20) NOT NULL,
    `auth_provider` VARCHAR(20) NOT NULL,
    `password_hash` TEXT NULL,
    `external_subject` VARCHAR(255) NULL,
    `status` VARCHAR(32) NOT NULL,
    `last_login_at` DATETIME(6) NULL,
    `must_change_password` BOOLEAN NOT NULL DEFAULT FALSE,
    `token_version` BIGINT NOT NULL DEFAULT 0,
    `failed_login_attempts` INT NOT NULL DEFAULT 0,
    `locked_until` DATETIME(6) NULL,
    `password_changed_at` DATETIME(6) NULL,
    `created_at` DATETIME(6) NOT NULL,
    `updated_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`account_id`),
    CONSTRAINT `UQ_Account_account_code` UNIQUE (`account_code`),
    CONSTRAINT `UQ_Account_username` UNIQUE (`username`),
    CONSTRAINT `UQ_Account_email` UNIQUE (`email`),
    CONSTRAINT `UQ_Account_phone` UNIQUE (`phone`),
    CONSTRAINT `CK_Account_account_type` CHECK (`account_type` IN ('ADMIN', 'STAFF')),
    CONSTRAINT `CK_Account_auth_provider` CHECK (`auth_provider` IN ('LOCAL')),
    CONSTRAINT `CK_Account_status` CHECK (`status` IN ('ACTIVE', 'INACTIVE', 'LOCKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `StaffProfile` (
    `staff_profile_id` BINARY(16) NOT NULL,
    `account_id` BINARY(16) NOT NULL,
    `staff_code` VARCHAR(64) NOT NULL,
    `department_code` VARCHAR(64) NULL,
    `job_title` VARCHAR(100) NULL,
    `contact_extension` VARCHAR(20) NULL,
    `is_active` BOOLEAN NOT NULL DEFAULT TRUE,
    `created_at` DATETIME(6) NOT NULL,
    `updated_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`staff_profile_id`),
    CONSTRAINT `UQ_StaffProfile_account` UNIQUE (`account_id`),
    CONSTRAINT `UQ_StaffProfile_staff_code` UNIQUE (`staff_code`),
    CONSTRAINT `FK_StaffProfile_account` FOREIGN KEY (`account_id`) REFERENCES `Account` (`account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `RefreshToken` (
    `refresh_token_id` BINARY(16) NOT NULL,
    `account_id` BINARY(16) NOT NULL,
    `token_hash` CHAR(64) NOT NULL,
    `token_family` BINARY(16) NOT NULL,
    `issued_at` DATETIME(6) NOT NULL,
    `expires_at` DATETIME(6) NOT NULL,
    `revoked_at` DATETIME(6) NULL,
    `replaced_by_token_id` BINARY(16) NULL,
    `device_info` VARCHAR(500) NULL,
    `ip_address` VARCHAR(45) NULL,
    `created_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`refresh_token_id`),
    CONSTRAINT `UQ_RefreshToken_token_hash` UNIQUE (`token_hash`),
    CONSTRAINT `FK_RefreshToken_account` FOREIGN KEY (`account_id`) REFERENCES `Account` (`account_id`),
    CONSTRAINT `FK_RefreshToken_replacement` FOREIGN KEY (`replaced_by_token_id`) REFERENCES `RefreshToken` (`refresh_token_id`),
    INDEX `IX_RefreshToken_account_active` (`account_id`, `revoked_at`, `expires_at`),
    INDEX `IX_RefreshToken_family` (`token_family`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `RevokedToken` (
    `revoked_token_id` BINARY(16) NOT NULL,
    `jti` VARCHAR(64) NOT NULL,
    `account_id` BINARY(16) NOT NULL,
    `expires_at` DATETIME(6) NOT NULL,
    `revoked_at` DATETIME(6) NOT NULL,
    `revocation_reason` VARCHAR(50) NOT NULL,
    `created_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`revoked_token_id`),
    CONSTRAINT `UQ_RevokedToken_jti` UNIQUE (`jti`),
    CONSTRAINT `FK_RevokedToken_account` FOREIGN KEY (`account_id`) REFERENCES `Account` (`account_id`),
    INDEX `IX_RevokedToken_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `AuditLog` (
    `audit_log_id` BINARY(16) NOT NULL,
    `account_id` BINARY(16) NULL,
    `action_code` VARCHAR(80) NOT NULL,
    `entity_type` VARCHAR(80) NOT NULL,
    `entity_id` BINARY(16) NULL,
    `before_data_json` JSON NULL,
    `after_data_json` JSON NULL,
    `ip_address` VARCHAR(45) NULL,
    `user_agent` TEXT NULL,
    `correlation_id` VARCHAR(100) NULL,
    `occurred_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`audit_log_id`),
    CONSTRAINT `FK_AuditLog_account` FOREIGN KEY (`account_id`) REFERENCES `Account` (`account_id`),
    INDEX `IX_AuditLog_entity` (`entity_type`, `entity_id`, `occurred_at`),
    INDEX `IX_AuditLog_account_time` (`account_id`, `occurred_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `SystemSetting` (
    `system_setting_id` BINARY(16) NOT NULL,
    `updated_by_account_id` BINARY(16) NULL,
    `setting_key` VARCHAR(100) NOT NULL,
    `setting_value_json` JSON NOT NULL,
    `data_type` VARCHAR(20) NOT NULL,
    `category` VARCHAR(64) NOT NULL,
    `description` TEXT NULL,
    `is_public` BOOLEAN NOT NULL DEFAULT FALSE,
    `updated_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`system_setting_id`),
    CONSTRAINT `UQ_SystemSetting_setting_key` UNIQUE (`setting_key`),
    CONSTRAINT `CK_SystemSetting_data_type` CHECK (`data_type` IN ('STRING', 'NUMBER', 'BOOLEAN', 'OBJECT', 'ARRAY')),
    CONSTRAINT `FK_SystemSetting_updated_by` FOREIGN KEY (`updated_by_account_id`) REFERENCES `Account` (`account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
