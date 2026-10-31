ALTER TABLE RobotCommandOutbox
    ADD COLUMN command_type VARCHAR(24) NOT NULL DEFAULT 'EXECUTE_STEP',
    ADD COLUMN interruption_reason VARCHAR(32) NULL,
    ADD COLUMN physical_stop_confirmed BOOLEAN NULL,
    ADD COLUMN result_envelope_json JSON NULL,
    ADD UNIQUE KEY UQ_RobotCommand_step_type (job_step_id,command_type);
ALTER TABLE RobotCommandOutbox DROP INDEX UQ_RobotCommand_step;

ALTER TABLE JobStep ADD COLUMN interruption_reason VARCHAR(32) NULL,
    ADD COLUMN interruption_requested_at DATETIME(6) NULL;

CREATE TABLE MqttOperationRecoveryReceipt (
    receipt_id BINARY(16) PRIMARY KEY,
    job_step_id BINARY(16) NOT NULL,
    actor_account_id BINARY(16) NOT NULL,
    idempotency_key VARCHAR(150) COLLATE utf8mb4_bin NOT NULL,
    request_json JSON NOT NULL,
    response_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY UQ_MqttRecovery_step_key (job_step_id,idempotency_key),
    FOREIGN KEY (job_step_id) REFERENCES JobStep(job_step_id),
    FOREIGN KEY (actor_account_id) REFERENCES Account(account_id)
);
