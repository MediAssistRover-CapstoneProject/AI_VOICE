package com.mediassistrover.backend;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Upgrade a disposable schema, never clean the caller's database. Requires local test credentials with CREATE privilege. */
@EnabledIfEnvironmentVariable(named="TEST_DB_URL",matches="jdbc:mysql://localhost(:[0-9]+)?/be_mar_phase10_[^?]+(\\?.*)?")
class Phase23MigrationIntegrationTest extends MySqlIntegrationTest {
    @Autowired Environment environment;

    @Test void upgradesCompiledV22DraftsWithoutChangingLegacyOrAuthoredDrafts() throws Exception {
        String source=System.getenv("TEST_DB_URL"),schema="phase23_migration_"+UUID.randomUUID().toString().replace("-","");
        assertThat(schema).matches("phase23_migration_[0-9a-f]{32}");
        int query=source.indexOf('?');String bare=query<0?source:source.substring(0,query),suffix=query<0?"":source.substring(query);
        String url=bare.substring(0,bare.lastIndexOf('/')+1)+schema+suffix;
        String username=environment.getRequiredProperty("spring.datasource.username"),password=environment.getRequiredProperty("spring.datasource.password");
        try(Connection admin=DriverManager.getConnection(source,username,password)) {
            try(Statement sql=admin.createStatement()){sql.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");}
            try {
                Flyway.configure().dataSource(url,username,password).locations("classpath:db/migration").target("22").load().migrate();
                try(Connection connection=DriverManager.getConnection(url,username,password);Statement sql=connection.createStatement()) {
                    List<String> legacy=legacy(connection);
                    sql.executeUpdate("UPDATE Workflow SET compiled_definition_json=JSON_OBJECT('old',true),definition_hash=REPEAT('b',64),description=REPLACE(description,'[MAR-STEP-2 DRAFT]','[MAR-STEP-2 COMPILED DRAFT]') WHERE schema_version=3");
                    // An administrator's draft with the same workflow code must retain its authored metadata.
                    sql.executeUpdate("INSERT INTO Workflow(workflow_id,workflow_code,version_no,workflow_name,service_type,description,input_schema_json,input_ui_json,default_inputs_json,output_mapping_json,required_capabilities_json,max_duration_sec,max_total_visits,graph_version,schema_version,status,accepts_new_jobs,row_version,compiled_definition_json,definition_hash,created_at,updated_at) SELECT UNHEX(REPLACE(UUID(),'-','')),workflow_code,99,workflow_name,service_type,'Authored draft',input_schema_json,input_ui_json,default_inputs_json,output_mapping_json,required_capabilities_json,max_duration_sec,max_total_visits,3,3,'DRAFT',FALSE,0,JSON_OBJECT('authored',true),REPEAT('c',64),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM Workflow WHERE workflow_code='WF_SECURE_MEDICAL_DELIVERY' AND schema_version=3");
                    Flyway.configure().dataSource(url,username,password).locations("classpath:db/migration").load().migrate();
                    assertThat(legacy(connection)).isEqualTo(legacy);
                    try(ResultSet rows=sql.executeQuery("SELECT description,compiled_definition_json,definition_hash,status FROM Workflow WHERE workflow_code='WF_SECURE_MEDICAL_DELIVERY' AND schema_version=3 AND version_no<>99")) {
                        assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).startsWith("[MAR-STEP-2 DRAFT]");
                        assertThat(rows.getString(2)).isNull();assertThat(rows.getString(3)).isNull();assertThat(rows.getString(4)).isEqualTo("DRAFT");
                    }
                    try(ResultSet rows=sql.executeQuery("SELECT compiled_definition_json,definition_hash FROM Workflow WHERE workflow_code='WF_SECURE_MEDICAL_DELIVERY' AND version_no=99")) {
                        assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).contains("authored");assertThat(rows.getString(2)).isEqualTo("c".repeat(64));
                    }
                    try(ResultSet rows=sql.executeQuery("SELECT JSON_UNQUOTE(JSON_EXTRACT(s.input_mapping_json,'$.authorization.purpose')) FROM Step s JOIN Workflow w ON w.workflow_id=s.workflow_id WHERE w.workflow_code='WF_SECURE_MEDICAL_DELIVERY' AND w.schema_version=3 AND s.step_code LIKE 'UNLOCK_%' ORDER BY s.step_code")) {
                        assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("DELIVERY_RECEIVER");
                        assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("DELIVERY_SENDER");assertThat(rows.next()).isFalse();
                    }
                }
            } finally {
                try(Statement sql=admin.createStatement()){sql.execute("DROP DATABASE `"+schema+"`");}
            }
        }
    }
    private List<String> legacy(Connection connection) throws SQLException {
        List<String> values=new ArrayList<>();
        try(Statement sql=connection.createStatement();ResultSet rows=sql.executeQuery("SELECT HEX(s.step_id),CAST(s.params_json AS CHAR),CAST(s.input_mapping_json AS CHAR) FROM Step s JOIN Workflow w ON w.workflow_id=s.workflow_id WHERE w.schema_version=2 ORDER BY s.step_id")) {
            while(rows.next())values.add(rows.getString(1)+":"+rows.getString(2)+":"+rows.getString(3));
        }
        return values;
    }
}
