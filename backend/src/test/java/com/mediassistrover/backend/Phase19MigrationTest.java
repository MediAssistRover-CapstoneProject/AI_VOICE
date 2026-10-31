package com.mediassistrover.backend;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class Phase19MigrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("backend_robot_v1")
            .withUsername("test")
            .withPassword("test");

    @Test
    void existingV16SchemaUpgradesToV17WithoutChangingHospitalFloorRelation() throws Exception {
        Flyway v16 = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("16"))
                .cleanDisabled(true)
                .load();
        assertThat(v16.migrate().targetSchemaVersion).isEqualTo("16");

        try (var connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("""
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema=DATABASE() AND table_name='HospitalInformationArticle'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isZero();
            }
        }

        Flyway latest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("17"))
                .cleanDisabled(true)
                .load();
        assertThat(latest.migrate().targetSchemaVersion).isEqualTo("17");

        try (var connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("""
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema=DATABASE()
                      AND table_name='HospitalInformationArticle'
                      AND column_name IN ('article_id','hospital_information_id')
                      AND column_type='binary(16)'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(2);
            }
            try (var result = statement.executeQuery("""
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema=DATABASE()
                      AND table_name='Floor'
                      AND column_name='hospital_information_id'
                      AND column_type='binary(16)'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isOne();
            }
        }
    }
}
