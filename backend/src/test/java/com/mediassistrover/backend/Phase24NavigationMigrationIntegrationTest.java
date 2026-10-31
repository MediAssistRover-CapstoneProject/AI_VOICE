package com.mediassistrover.backend;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="TEST_DB_URL",matches="jdbc:mysql://localhost(:[0-9]+)?/be_mar_phase11_[^?]+(\\?.*)?")
class Phase24NavigationMigrationIntegrationTest extends MySqlIntegrationTest {
    @Autowired Environment environment;
    @Test void upgradesV23CompiledDraftsAndPreservesLegacyAndAuthoredWorkflowDefinitions() throws Exception {
        String source=System.getenv("TEST_DB_URL"),schema="phase24_migration_"+UUID.randomUUID().toString().replace("-","");
        assertThat(schema).matches("phase24_migration_[0-9a-f]{32}");
        int query=source.indexOf('?');String base=query<0?source:source.substring(0,query),suffix=query<0?"":source.substring(query);
        String url=base.substring(0,base.lastIndexOf('/')+1)+schema+suffix;
        String user=environment.getRequiredProperty("spring.datasource.username"),password=environment.getRequiredProperty("spring.datasource.password");
        try(Connection admin=DriverManager.getConnection(source,user,password);Statement control=admin.createStatement()) {
            control.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            try {
                Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").target("23").load().migrate();
                try(Connection db=DriverManager.getConnection(url,user,password);Statement sql=db.createStatement()) {
                    List<String> legacy=legacy(db);
                    sql.executeUpdate("UPDATE Workflow SET compiled_definition_json=JSON_OBJECT('compiled',true),definition_hash=REPEAT('b',64),description=REPLACE(description,'[MAR-STEP-2 DRAFT]','[MAR-STEP-2 COMPILED DRAFT]') WHERE schema_version=3");
                    sql.executeUpdate("INSERT INTO Workflow(workflow_id,workflow_code,version_no,workflow_name,service_type,description,input_schema_json,input_ui_json,default_inputs_json,output_mapping_json,required_capabilities_json,max_duration_sec,max_total_visits,graph_version,schema_version,status,accepts_new_jobs,row_version,compiled_definition_json,definition_hash,created_at,updated_at) SELECT UNHEX(REPLACE(UUID(),'-','')),workflow_code,99,workflow_name,service_type,'Authored draft',input_schema_json,input_ui_json,default_inputs_json,output_mapping_json,required_capabilities_json,max_duration_sec,max_total_visits,3,3,'DRAFT',FALSE,0,JSON_OBJECT('authored',true),REPEAT('c',64),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM Workflow WHERE schema_version=3 AND workflow_code IN ('WF_NAVIGATION','WF_SECURE_MEDICAL_DELIVERY')");
                    Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").load().migrate();
                    assertThat(legacy(db)).isEqualTo(legacy);
                    assertThat(count(sql,"SELECT COUNT(*) FROM Workflow WHERE schema_version=3 AND workflow_code IN ('WF_NAVIGATION','WF_SECURE_MEDICAL_DELIVERY') AND version_no<>99 AND compiled_definition_json IS NULL AND definition_hash IS NULL AND description LIKE '[MAR-STEP-2 DRAFT]%' AND status='DRAFT' AND accepts_new_jobs=FALSE")).isEqualTo(2);
                    assertThat(count(sql,"SELECT COUNT(*) FROM Workflow WHERE version_no=99 AND description='Authored draft' AND definition_hash=REPEAT('c',64) AND JSON_EXTRACT(compiled_definition_json,'$.authored')=TRUE")).isEqualTo(2);
                    assertThat(count(sql,"SELECT COUNT(*) FROM Step s JOIN Workflow w ON w.workflow_id=s.workflow_id WHERE w.workflow_code='WF_SECURE_MEDICAL_DELIVERY' AND w.schema_version=3 AND s.step_code LIKE 'GROUP_%'")).isEqualTo(7);
                    assertThat(count(sql,"SELECT COUNT(*) FROM StepTransition t JOIN Step s ON s.step_id=t.from_step_id JOIN Workflow w ON w.workflow_id=s.workflow_id JOIN Step n ON n.step_id=t.to_step_id WHERE w.schema_version=3 AND s.step_code IN ('NAVIGATE_GROUP','GROUP_MOVE') AND t.outcome='FAILURE' AND t.is_default=FALSE AND JSON_UNQUOTE(JSON_EXTRACT(t.condition_json,'$.path'))='/error/errorCode' AND JSON_UNQUOTE(JSON_EXTRACT(t.condition_json,'$.value'))='PATH_UNAVAILABLE' AND n.step_code IN ('NEXT_TARGET','GROUP_NEXT')")).isEqualTo(2);
                    try(ResultSet row=sql.executeQuery("SELECT input_schema_json FROM Workflow WHERE workflow_code='WF_SECURE_MEDICAL_DELIVERY' AND schema_version=3 AND version_no<>99")) {
                        assertThat(row.next()).isTrue();assertThat(row.getString(1)).contains("delivery_group_id","ENDPOINT_GROUP","oneOf");
                    }
                }
            } finally {control.execute("DROP DATABASE `"+schema+"`");}
        }
    }
    private long count(Statement sql,String query) throws SQLException {try(ResultSet row=sql.executeQuery(query)){row.next();return row.getLong(1);}}
    private List<String> legacy(Connection db) throws SQLException {
        List<String> rows=new ArrayList<>();
        for(String query:List.of("SELECT CONCAT(HEX(step_id),':',CAST(params_json AS CHAR),':',CAST(input_mapping_json AS CHAR)) FROM Step s JOIN Workflow w ON w.workflow_id=s.workflow_id WHERE w.schema_version=2 ORDER BY step_id",
                "SELECT CONCAT(HEX(transition_id),':',COALESCE(HEX(to_step_id),'NULL'),':',COALESCE(CAST(condition_json AS CHAR),'NULL')) FROM StepTransition t JOIN Workflow w ON w.workflow_id=t.workflow_id WHERE w.schema_version=2 ORDER BY transition_id"))
            try(Statement sql=db.createStatement();ResultSet result=sql.executeQuery(query)){while(result.next())rows.add(result.getString(1));}
        return rows;
    }
}
