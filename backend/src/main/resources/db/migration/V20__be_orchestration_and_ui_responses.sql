ALTER TABLE Job ADD COLUMN be_orchestrated BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN selected_job_step_id BINARY(16) NULL,
    ADD CONSTRAINT FK_Job_selected_step FOREIGN KEY (selected_job_step_id) REFERENCES JobStep(job_step_id);

ALTER TABLE JobStep ADD COLUMN selected_at DATETIME(6) NULL;

CREATE TABLE WorkflowOrchestrationWork (
    job_id BINARY(16) PRIMARY KEY,
    requested_generation BIGINT NOT NULL DEFAULT 0,
    processed_generation BIGINT NOT NULL DEFAULT 0,
    requested_at DATETIME(6) NOT NULL,
    FOREIGN KEY (job_id) REFERENCES Job(job_id)
);

CREATE TABLE JobStepResponse (
    response_id BINARY(16) PRIMARY KEY,
    job_step_id BINARY(16) NOT NULL,
    actor_account_id BINARY(16) NOT NULL,
    idempotency_key VARCHAR(150) COLLATE utf8mb4_bin NOT NULL,
    request_json JSON NOT NULL,
    result_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT UQ_JobStepResponse_key UNIQUE (job_step_id, idempotency_key),
    FOREIGN KEY (job_step_id) REFERENCES JobStep(job_step_id),
    FOREIGN KEY (actor_account_id) REFERENCES Account(account_id)
);
