package com.mediassistrover.backend;

import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class Phase14IntegrationTest extends MySqlIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkflowRepository workflowRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired AuditLogRepository auditRepository;
    @Autowired ReportService reportService;

    private Account admin;
    private Workflow workflow;
    private final Instant from = Instant.parse("2026-10-01T00:00:00Z");
    private final Instant to = Instant.parse("2026-10-03T00:00:00Z");

    @BeforeEach
    void setup() {
        clean();
        admin = accountRepository.save(Account.builder().accountCode("P14-ADMIN").username("phase14.admin")
                .fullName("Phase 14 Admin").email("phase14.admin@example.test").role(Role.ADMIN)
                .authProvider(AuthProvider.LOCAL).passwordHash("unused").status(AccountStatus.ACTIVE)
                .passwordChangedAt(Instant.now()).build());
        workflow = workflowRepository.findAll().getFirst();
        insertJob("P14-SUCCESS", JobStatus.SUCCEEDED, from.plusSeconds(3600), 60);
        insertJob("P14-FAILED", JobStatus.FAILED, from.plusSeconds(7200), 120);
        insertJob("P14-RECOVERY", JobStatus.RECOVERY_REQUIRED, from.plusSeconds(90000), 300);
    }

    @AfterEach
    void cleanup() { clean(); }

    @Test
    void calculatesDocumentedMetricsBucketsAndPercentiles() {
        var report = reportService.getAnalytics(from, to, "Asia/Ho_Chi_Minh", ReportInterval.DAY);

        assertThat(report.timezone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(report.jobs().total()).isEqualTo(3);
        assertThat(report.jobs().succeeded()).isEqualTo(1);
        assertThat(report.jobs().failed()).isEqualTo(1);
        assertThat(report.jobs().recoveryRequired()).isEqualTo(1);
        assertThat(report.jobs().successRatePercent()).isEqualTo(33.33);
        assertThat(report.jobs().averageDurationSeconds()).isEqualTo(160.0);
        assertThat(report.jobs().p50DurationSeconds()).isEqualTo(120.0);
        assertThat(report.jobs().p95DurationSeconds()).isEqualTo(300.0);
        assertThat(report.timeline()).hasSize(2);
        assertThat(report.timeline()).extracting(bucket -> bucket.jobs()).containsExactly(2L, 1L);
        assertThat(report.jobsByWorkflow()).singleElement().satisfies(item -> assertThat(item.total()).isEqualTo(3));
        assertThat(report.deliveries().total()).isZero();
        assertThat(report.emergencies().total()).isZero();
        assertThat(report.monitoring().detectionEvents()).isZero();
        assertThat(report.activityByMap()).isEmpty();
        assertThat(report.activityByZone()).isEmpty();
    }

    @Test
    void exposesAnalyticsIndexesToTheMysqlQueryPlanner() {
        var indexes = jdbc.queryForList("""
                SELECT DISTINCT INDEX_NAME FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name='Job'
                """, String.class);
        assertThat(indexes).contains("IX_Job_queued_at", "IX_Job_workflow_time");

        var plan = jdbc.queryForMap("EXPLAIN SELECT job_id FROM `Job` WHERE queued_at>=? AND queued_at<?",
                Timestamp.from(from), Timestamp.from(to));
        assertThat(String.valueOf(plan.get("possible_keys"))).contains("IX_Job_queued_at");
    }

    @Test
    void providesPagedDrillDownAndAuditedBoundedCsv() {
        var page = reportService.getJobDetails(from, to, workflow.getWorkflowId(), null, null,
                PageRequest.of(0, 2));
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(2);

        var csv = reportService.exportJobs(from, to, workflow.getWorkflowId(), null, JobStatus.SUCCEEDED,
                admin.getAccountId(), new RequestMetadata("127.0.0.1", "test", "phase14-test"));
        assertThat(new String(csv.content())).contains("jobId,jobCode,status").contains("P14-SUCCESS")
                .doesNotContain("P14-FAILED");
        assertThat(csv.rowCount()).isEqualTo(1);
        assertThat(auditRepository.findAll()).anyMatch(log -> log.getActionCode().equals("REPORT_CSV_EXPORTED"));
    }

    @Test
    void rejectsInvalidTimezoneAndUnboundedHourlyRange() {
        assertThatThrownBy(() -> reportService.getAnalytics(from, to, "Invalid/Timezone", ReportInterval.DAY))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Invalid timezone");
        assertThatThrownBy(() -> reportService.getAnalytics(from, from.plusSeconds(32L * 86400), "UTC", ReportInterval.HOUR))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("too large");
    }

    private void insertJob(String code, JobStatus status, Instant queuedAt, int durationSeconds) {
        UUID id = UUID.randomUUID();
        Instant startedAt = queuedAt.plusSeconds(10);
        Instant endedAt = startedAt.plusSeconds(durationSeconds);
        jdbc.update("""
                INSERT INTO `Job` (job_id,workflow_id,job_code,status,priority,input_json,context_json,workflow_hash,
                    protocol_version,schema_version,graph_version,queued_at,started_at,ended_at,row_version)
                VALUES (UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?,JSON_OBJECT(),JSON_OBJECT(),?,1,1,1,?,?,?,0)
                """, id.toString(), workflow.getWorkflowId().toString(), code, status.name(), 50, "a".repeat(64),
                Timestamp.from(queuedAt), Timestamp.from(startedAt), Timestamp.from(endedAt));
    }

    private void clean() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String table : new String[]{"JobEvent", "JobStep", "JobTask", "Job", "AuditLog", "RevokedToken",
                "RefreshToken", "StaffProfile", "Account"}) jdbc.execute("TRUNCATE TABLE `" + table + "`");
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
    }
}
