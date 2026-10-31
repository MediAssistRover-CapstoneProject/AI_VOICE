package com.mediassistrover.backend;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class MySqlIntegrationTest {

    static final String EXTERNAL_URL = System.getenv("TEST_DB_URL");
    static final MySQLContainer<?> MYSQL;

    static {
        if (EXTERNAL_URL == null || EXTERNAL_URL.isBlank()) {
            MYSQL = new MySQLContainer<>("mysql:8.4")
                    .withDatabaseName("backend_robot_v1")
                    .withUsername("test")
                    .withPassword("test");
            MYSQL.start();
        } else {
            MYSQL = null;
        }
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> EXTERNAL_URL == null || EXTERNAL_URL.isBlank()
                ? MYSQL.getJdbcUrl() : EXTERNAL_URL);
        registry.add("spring.datasource.username", () -> externalOrDefault("TEST_DB_USERNAME", "test",
                MYSQL == null ? null : MYSQL.getUsername()));
        registry.add("spring.datasource.password", () -> externalOrDefault("TEST_DB_PASSWORD", "test",
                MYSQL == null ? null : MYSQL.getPassword()));
    }

    private static String externalOrDefault(String name, String externalDefault, String containerValue) {
        if (EXTERNAL_URL == null || EXTERNAL_URL.isBlank()) {
            return containerValue;
        }
        String value = System.getenv(name);
        return value == null ? externalDefault : value;
    }
}
