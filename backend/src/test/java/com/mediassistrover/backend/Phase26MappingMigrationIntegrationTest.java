package com.mediassistrover.backend;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="TEST_DB_URL",matches="jdbc:mysql://localhost(:[0-9]+)?/be_mar_phase12_[^?]+(\\?.*)?")
class Phase26MappingMigrationIntegrationTest extends MySqlIntegrationTest {
    @Autowired Environment environment;
    @Test void upgradesV25WithoutRewritingExistingGraphsAndAddsOnlyMappingDraft() throws Exception {
        String source=System.getenv("TEST_DB_URL"),schema="phase26_migration_"+UUID.randomUUID().toString().replace("-","");
        assertThat(schema).matches("phase26_migration_[0-9a-f]{32}");int query=source.indexOf('?');String base=query<0?source:source.substring(0,query),suffix=query<0?"":source.substring(query);
        String url=base.substring(0,base.lastIndexOf('/')+1)+schema+suffix;
        String user=environment.getRequiredProperty("spring.datasource.username"),password=environment.getRequiredProperty("spring.datasource.password");
        try(Connection admin=DriverManager.getConnection(source,user,password);Statement control=admin.createStatement()) {
            control.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            try {
                Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").target("25").load().migrate();
                try(Connection db=DriverManager.getConnection(url,user,password);Statement sql=db.createStatement()) {
                    List<String> before=snapshot(sql);
                    Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").load().migrate();
                    assertThat(snapshot(sql)).isEqualTo(before);
                    assertThat(count(sql,"SELECT COUNT(*) FROM Workflow WHERE workflow_code='WF_MAPPING' AND schema_version=3 AND graph_version=3 AND status='DRAFT' AND accepts_new_jobs=FALSE")).isEqualTo(1);
                    assertThat(count(sql,"SELECT COUNT(*) FROM Step s JOIN Workflow w ON w.workflow_id=s.workflow_id WHERE w.workflow_code='WF_MAPPING' AND s.step_type_code='EXECUTE_SENSING' AND s.max_attempts=1")).isEqualTo(3);
                    assertThat(count(sql,"SELECT COUNT(*) FROM StepTransition t JOIN Workflow w ON w.workflow_id=t.workflow_id WHERE w.workflow_code='WF_MAPPING'")).isEqualTo(15);
                    assertThat(count(sql,"SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='JobStep' AND column_name='mapping_cleanup' AND column_default='0'")).isEqualTo(1);
                    assertThat(count(sql,"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('MappingSession','MapArtifact')")).isEqualTo(2);
                    Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").load().validate();
                }
            } finally {control.execute("DROP DATABASE `"+schema+"`");}
        }
    }
    private int count(Statement sql,String query) throws SQLException {try(var rows=sql.executeQuery(query)){rows.next();return rows.getInt(1);}}
    private List<String> snapshot(Statement sql) throws SQLException {
        List<String> result=new ArrayList<>();
        for(String query:List.of("SELECT CONCAT(HEX(workflow_id),':',CAST(input_schema_json AS CHAR),':',status) FROM Workflow WHERE workflow_code<>'WF_MAPPING' ORDER BY workflow_id",
                "SELECT CONCAT(HEX(s.step_id),':',CAST(s.params_json AS CHAR),':',CAST(s.input_mapping_json AS CHAR)) FROM Step s JOIN Workflow w ON w.workflow_id=s.workflow_id WHERE w.workflow_code<>'WF_MAPPING' ORDER BY s.step_id",
                "SELECT CONCAT(HEX(t.transition_id),':',IFNULL(HEX(t.to_step_id),''),':',outcome,':',IFNULL(CAST(condition_json AS CHAR),'')) FROM StepTransition t JOIN Workflow w ON w.workflow_id=t.workflow_id WHERE w.workflow_code<>'WF_MAPPING' ORDER BY t.transition_id"))
            try(var rows=sql.executeQuery(query)){while(rows.next())result.add(rows.getString(1));}return result;
    }
}
