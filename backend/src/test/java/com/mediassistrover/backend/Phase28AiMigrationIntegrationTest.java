package com.mediassistrover.backend;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="TEST_DB_URL",matches="jdbc:mysql://localhost(:[0-9]+)?/be_mar_phase13_[^?]+(\\?.*)?")
class Phase28AiMigrationIntegrationTest extends MySqlIntegrationTest {
    @Autowired Environment environment;
    @Test void upgradesV27PreservingLegacyAndAuthoredGraphsWhileAddingAiReceiptsAndReviewInputs() throws Exception {
        String source=System.getenv("TEST_DB_URL"),schema="phase28_migration_"+UUID.randomUUID().toString().replace("-","");assertThat(schema).matches("phase28_migration_[0-9a-f]{32}");
        int query=source.indexOf('?');String base=query<0?source:source.substring(0,query),suffix=query<0?"":source.substring(query);String url=base.substring(0,base.lastIndexOf('/')+1)+schema+suffix;
        String user=environment.getRequiredProperty("spring.datasource.username"),password=environment.getRequiredProperty("spring.datasource.password");
        try(Connection admin=DriverManager.getConnection(source,user,password);Statement control=admin.createStatement()) {
            control.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            try {
                Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").target("27").load().migrate();
                try(Connection db=DriverManager.getConnection(url,user,password);Statement sql=db.createStatement()) {
                    var before=legacy(sql);
                    sql.executeUpdate("UPDATE Workflow SET compiled_definition_json=JSON_OBJECT('compiled',true),definition_hash=REPEAT('b',64),description=REPLACE(description,'[MAR-STEP-2 DRAFT]','[MAR-STEP-2 COMPILED DRAFT]') WHERE schema_version=3");
                    sql.executeUpdate("INSERT INTO Workflow(workflow_id,workflow_code,version_no,workflow_name,service_type,description,input_schema_json,input_ui_json,default_inputs_json,output_mapping_json,required_capabilities_json,max_duration_sec,max_total_visits,graph_version,schema_version,status,accepts_new_jobs,row_version,compiled_definition_json,definition_hash,created_at,updated_at) SELECT UNHEX(REPLACE(UUID(),'-','')),workflow_code,99,workflow_name,service_type,'Authored draft',input_schema_json,input_ui_json,default_inputs_json,output_mapping_json,required_capabilities_json,max_duration_sec,max_total_visits,3,3,'DRAFT',FALSE,0,JSON_OBJECT('authored',true),REPEAT('c',64),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM Workflow WHERE workflow_code='WF_FALL_DETECTION_RESPONSE' AND schema_version=3");
                    Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").load().migrate();assertThat(legacy(sql)).isEqualTo(before);
                    assertThat(count(sql,"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('AiSensingProfile','AiObservation','MonitoringIngestionReceipt')")).isEqualTo(3);
                    assertThat(count(sql,"SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='RobotCommandOutbox' AND column_name='ai_profile_snapshot_json'")).isEqualTo(1);
                    assertThat(count(sql,"SELECT COUNT(*) FROM Step s JOIN Workflow w ON w.workflow_id=s.workflow_id WHERE w.schema_version=3 AND w.workflow_code='WF_FALL_DETECTION_RESPONSE' AND s.step_code='STAFF_REVIEW' AND JSON_UNQUOTE(JSON_EXTRACT(s.params_json,'$.action'))='CONFIRM' AND JSON_UNQUOTE(JSON_EXTRACT(s.params_json,'$.required_role'))='STAFF'")).isEqualTo(1);
                    assertThat(count(sql,"SELECT COUNT(*) FROM Step s JOIN Workflow w ON w.workflow_id=s.workflow_id WHERE w.schema_version=3 AND w.workflow_code='WF_PATIENT_ESCORT' AND s.step_code='MOVE' AND JSON_UNQUOTE(JSON_EXTRACT(s.params_json,'$.mode'))='ESCORT'")).isEqualTo(1);
                    assertThat(count(sql,"SELECT COUNT(*) FROM Workflow WHERE workflow_code='WF_FALL_DETECTION_RESPONSE' AND version_no=99 AND definition_hash=REPEAT('c',64) AND JSON_EXTRACT(compiled_definition_json,'$.authored')=TRUE")).isEqualTo(1);
                    assertThat(count(sql,"SELECT COUNT(*) FROM Workflow WHERE schema_version=3 AND workflow_code='WF_MAPPING' AND definition_hash=REPEAT('b',64)")).isEqualTo(1);
                    assertThat(count(sql,"SELECT COUNT(*) FROM Workflow WHERE schema_version=3 AND workflow_code='WF_FALL_DETECTION_RESPONSE' AND version_no<>99 AND status='DRAFT' AND accepts_new_jobs=FALSE AND compiled_definition_json IS NULL AND definition_hash IS NULL")).isEqualTo(1);
                    Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").load().validate();
                }
            } finally{control.execute("DROP DATABASE `"+schema+"`");}
        }
    }
    int count(Statement sql,String query)throws SQLException{try(var rows=sql.executeQuery(query)){rows.next();return rows.getInt(1);}}
    List<String> legacy(Statement sql)throws SQLException{List<String> result=new ArrayList<>();for(String query:List.of("SELECT CONCAT(HEX(w.workflow_id),':',CAST(w.input_schema_json AS CHAR),':',CAST(w.output_mapping_json AS CHAR)) FROM Workflow w WHERE schema_version=2 ORDER BY workflow_id",
            "SELECT CONCAT(HEX(s.step_id),':',CAST(s.params_json AS CHAR),':',CAST(s.input_mapping_json AS CHAR)) FROM Step s JOIN Workflow w ON w.workflow_id=s.workflow_id WHERE w.schema_version=2 ORDER BY s.step_id"))try(var rows=sql.executeQuery(query)){while(rows.next())result.add(rows.getString(1));}return result;}
}
