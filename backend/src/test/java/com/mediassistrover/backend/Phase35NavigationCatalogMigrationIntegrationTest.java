package com.mediassistrover.backend;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import java.sql.*;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named="TEST_DB_URL",matches="jdbc:mysql://localhost(:[0-9]+)?/be_mar_phase14_[^?]+(\\?.*)?")
class Phase35NavigationCatalogMigrationIntegrationTest extends MySqlIntegrationTest {
    @Autowired Environment environment;

    @Test void addsTwoFailClosedNavigationDraftsWithoutInventingEndpoints() throws Exception {
        String source=System.getenv("TEST_DB_URL"),schema="phase35_migration_"+UUID.randomUUID().toString().replace("-","");
        int query=source.indexOf('?');String base=query<0?source:source.substring(0,query),suffix=query<0?"":source.substring(query);
        String url=base.substring(0,base.lastIndexOf('/')+1)+schema+suffix;
        String user=environment.getRequiredProperty("spring.datasource.username"),password=environment.getRequiredProperty("spring.datasource.password");
        try(Connection admin=DriverManager.getConnection(source,user,password);Statement control=admin.createStatement()) {
            control.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            try {
                Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").target("34").load().migrate();
                try(Connection db=DriverManager.getConnection(url,user,password);Statement sql=db.createStatement()) {
                    long endpoints=count(sql,"SELECT COUNT(*) FROM Endpoint");
                    Flyway flyway=Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").load();
                    flyway.migrate();flyway.validate();
                    assertThat(count(sql,"SELECT COUNT(*) FROM Workflow WHERE workflow_code IN ('WF_DYNAMIC_ENDPOINT_NAVIGATION','WF_HOSPITAL_MULTI_STOP') AND version_no=1 AND service_type='NAVIGATION' AND schema_version=3 AND status='DRAFT' AND accepts_new_jobs=FALSE")).isEqualTo(2);
                    assertThat(count(sql,"SELECT COUNT(*) FROM Step s JOIN Workflow w ON w.workflow_id=s.workflow_id WHERE w.workflow_code IN ('WF_DYNAMIC_ENDPOINT_NAVIGATION','WF_HOSPITAL_MULTI_STOP') AND s.step_type_code='MOVE'")).isGreaterThanOrEqualTo(4);
                    assertThat(count(sql,"SELECT COUNT(*) FROM Workflow WHERE workflow_code='WF_DYNAMIC_ENDPOINT_NAVIGATION' AND JSON_CONTAINS(JSON_EXTRACT(input_schema_json,'$.required'),JSON_QUOTE('endpoint_id'))")).isEqualTo(1);
                    assertThat(count(sql,"SELECT COUNT(*) FROM Workflow WHERE workflow_code='WF_HOSPITAL_MULTI_STOP' AND JSON_CONTAINS(JSON_EXTRACT(input_schema_json,'$.required'),JSON_QUOTE('endpoint_group_id'))")).isEqualTo(1);
                    assertThat(count(sql,"SELECT COUNT(*) FROM Endpoint")).isEqualTo(endpoints);
                    assertThat(flyway.migrate().migrationsExecuted).isZero();
                }
            } finally {control.execute("DROP DATABASE `"+schema+"`");}
        }
    }

    private long count(Statement sql,String query)throws SQLException {
        try(ResultSet rows=sql.executeQuery(query)){rows.next();return rows.getLong(1);}
    }
}
