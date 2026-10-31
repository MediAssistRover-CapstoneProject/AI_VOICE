package com.mediassistrover.backend;

import com.mediassistrover.backend.entity.AuditLog;
import com.mediassistrover.backend.enums.RetentionMode;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.repository.AuditLogRepository;
import com.mediassistrover.backend.service.RetentionService;
import net.javacrumbs.shedlock.core.*;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class Phase13IntegrationTest extends MySqlIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AuditLogRepository auditRepository;
    @Autowired RetentionService retentionService;

    @BeforeEach
    void setup() {
        clean();
        setting("retention.execution_enabled", false);
        setting("retention.default_mode", "DISABLED");
        setting("retention.batch_size", 200);
        setting("retention.max_batches_per_run", 10);
    }

    @AfterEach
    void cleanup() { clean(); }

    @Test
    void dryRunIsNonDestructiveAndExecutionRequiresSafetySwitch() {
        AuditLog old = auditRepository.save(audit("OLD_DRY_RUN", Instant.now().minus(Duration.ofDays(1200))));
        setting("retention.default_mode", "DELETE");

        var dryRun = retentionService.run(true);
        var auditResult = result(dryRun, "audit_log");
        assertThat(auditResult.mode()).isEqualTo(RetentionMode.DELETE);
        assertThat(auditResult.eligible()).isEqualTo(1);
        assertThat(auditResult.deleted()).isZero();
        assertThat(auditRepository.existsById(old.getAuditLogId())).isTrue();

        assertThatThrownBy(() -> retentionService.run(false))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("execution is disabled");
    }

    @Test
    void deletesInBoundedBatchesAndArchivesBeforeDeletingSource() {
        setting("retention.execution_enabled", true);
        setting("retention.default_mode", "DELETE");
        setting("retention.batch_size", 1);
        setting("retention.max_batches_per_run", 1);
        AuditLog old1 = auditRepository.save(audit("OLD_DELETE_1", Instant.now().minus(Duration.ofDays(1201))));
        AuditLog old2 = auditRepository.save(audit("OLD_DELETE_2", Instant.now().minus(Duration.ofDays(1202))));
        AuditLog current = auditRepository.save(audit("CURRENT", Instant.now()));

        var deleteRun = retentionService.run(false);
        var deleteResult = result(deleteRun, "audit_log");
        assertThat(deleteResult.deleted()).isEqualTo(1);
        assertThat(deleteResult.truncated()).isTrue();
        assertThat(auditRepository.existsById(current.getAuditLogId())).isTrue();
        assertThat(auditRepository.existsById(old1.getAuditLogId()) || auditRepository.existsById(old2.getAuditLogId()))
                .isTrue();

        setting("retention.default_mode", "ARCHIVE");
        setting("retention.batch_size", 200);
        setting("retention.max_batches_per_run", 10);
        var archiveRun = retentionService.run(false);
        var archiveResult = result(archiveRun, "audit_log");
        assertThat(archiveResult.error()).isNull();
        assertThat(archiveResult.archived()).isEqualTo(1);
        assertThat(archiveResult.deleted()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `RetentionArchive` WHERE dataset_name='audit_log'",
                Integer.class)).isEqualTo(1);
        assertThat(auditRepository.existsById(current.getAuditLogId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT DATA_TYPE FROM information_schema.columns WHERE table_schema=DATABASE() " +
                "AND table_name='RetentionArchive' AND column_name='source_record_id'", String.class))
                .isEqualTo("binary");
    }

    @Test
    void sharedMysqlLockAllowsOneOwnerAndRecoversExpiredLock() throws Throwable {
        LockProvider provider1 = provider();
        LockProvider provider2 = provider();
        LockingTaskExecutor executor1 = new DefaultLockingTaskExecutor(provider1);
        LockingTaskExecutor executor2 = new DefaultLockingTaskExecutor(provider2);
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        String lockName = "phase13-two-instance-" + UUID.randomUUID();
        LockConfiguration configuration = new LockConfiguration(Instant.now(), lockName,
                Duration.ofMinutes(2), Duration.ZERO);

        Thread owner = new Thread(() -> executor1.executeWithLock((Runnable) () -> {
            executions.incrementAndGet();
            acquired.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        }, configuration));
        owner.start();
        assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();

        var contender = executor2.executeWithLock(() -> executions.incrementAndGet(), configuration);
        assertThat(contender.wasExecuted()).isFalse();
        assertThat(executions).hasValue(1);
        release.countDown();
        owner.join(5000);

        jdbc.update("UPDATE `ShedLock` SET lock_until=DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE name=?", lockName);
        LockConfiguration recovery = new LockConfiguration(Instant.now(), lockName,
                Duration.ofMinutes(2), Duration.ZERO);
        var recovered = executor2.executeWithLock(() -> executions.incrementAndGet(), recovery);
        assertThat(recovered.wasExecuted()).isTrue();
        assertThat(executions).hasValue(2);
    }

    private LockProvider provider() {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbc).withTableName("ShedLock").usingDbTime().build());
    }

    private com.mediassistrover.backend.dto.operations.response.RetentionResponses.DatasetResult result(
            com.mediassistrover.backend.dto.operations.response.RetentionResponses.RunResult run, String dataset) {
        return run.datasets().stream().filter(item -> item.dataset().equals(dataset)).findFirst().orElseThrow();
    }

    private AuditLog audit(String action, Instant occurredAt) {
        return AuditLog.builder().actionCode(action).entityType("Phase13Test")
                .correlationId(UUID.randomUUID().toString()).occurredAt(occurredAt).build();
    }

    private void setting(String key, Object value) {
        if (value instanceof Boolean bool) {
            jdbc.update("UPDATE `SystemSetting` SET setting_value_json=JSON_OBJECT('value', " +
                    (bool ? "true" : "false") + ") WHERE setting_key=?", key);
        } else {
            jdbc.update("UPDATE `SystemSetting` SET setting_value_json=JSON_OBJECT('value', ?) WHERE setting_key=?", value, key);
        }
    }

    private void clean() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        jdbc.execute("TRUNCATE TABLE `RetentionArchive`");
        jdbc.execute("TRUNCATE TABLE `ShedLock`");
        jdbc.execute("TRUNCATE TABLE `AuditLog`");
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
    }
}
