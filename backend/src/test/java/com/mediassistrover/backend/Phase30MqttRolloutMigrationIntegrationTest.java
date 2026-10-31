package com.mediassistrover.backend;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="TEST_DB_URL",matches="jdbc:mysql://localhost(:[0-9]+)?/be_mar_phase14_[^?]+(\\?.*)?")
class Phase30MqttRolloutMigrationIntegrationTest extends MySqlIntegrationTest {
    @Autowired Environment environment;
    @Test void upgradesV29WithoutChangingWorkflowsOrActiveJobTransportAndDefaultsToCompatibility()throws Exception {
        String source=System.getenv("TEST_DB_URL"),schema="phase30_migration_"+UUID.randomUUID().toString().replace("-","");
        assertThat(schema).matches("phase30_migration_[0-9a-f]{32}");
        int query=source.indexOf('?');String base=query<0?source:source.substring(0,query),suffix=query<0?"":source.substring(query);
        String url=base.substring(0,base.lastIndexOf('/')+1)+schema+suffix;
        String user=environment.getRequiredProperty("spring.datasource.username"),password=environment.getRequiredProperty("spring.datasource.password");
        try(Connection admin=DriverManager.getConnection(source,user,password);Statement control=admin.createStatement()) {
            control.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            try {
                Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").target("29").load().migrate();
                try(Connection db=DriverManager.getConnection(url,user,password);Statement sql=db.createStatement()) {
                    List<String> before=workflows(sql);
                    sql.executeUpdate("INSERT INTO Job(job_id,workflow_id,job_code,status,priority,input_json,context_json,workflow_hash,protocol_version,schema_version,graph_version,be_orchestrated,queued_at,row_version) SELECT UNHEX(MD5('phase30-legacy-job')),workflow_id,'PHASE30-LEGACY','RUNNING',1,JSON_OBJECT(),JSON_OBJECT(),REPEAT('a',64),1,2,3,FALSE,UTC_TIMESTAMP(6),0 FROM Workflow WHERE schema_version=2 LIMIT 1");
                    Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").load().migrate();
                    assertThat(workflows(sql)).isEqualTo(before);
                    try(var rows=sql.executeQuery("SELECT mode,dispatch_halted,revision FROM MqttRolloutControl WHERE control_id=1")){assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("LEGACY_COMPATIBLE");assertThat(rows.getBoolean(2)).isFalse();assertThat(rows.getLong(3)).isZero();assertThat(rows.next()).isFalse();}
                    try(var rows=sql.executeQuery("SELECT status,schema_version,be_orchestrated FROM Job WHERE job_code='PHASE30-LEGACY'")){assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("RUNNING");assertThat(rows.getInt(2)).isEqualTo(2);assertThat(rows.getBoolean(3)).isFalse();}
                    assertThatThrownBy(()->sql.executeUpdate("UPDATE MqttRolloutControl SET mode='REST_FALLBACK' WHERE control_id=1")).isInstanceOf(SQLException.class);
                    Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").load().validate();
                }
            }finally{control.execute("DROP DATABASE `"+schema+"`");}
        }
    }
    List<String> workflows(Statement sql)throws SQLException {
        List<String> result=new ArrayList<>();
        try(var rows=sql.executeQuery("SELECT CONCAT(HEX(workflow_id),':',schema_version,':',status,':',accepts_new_jobs,':',COALESCE(definition_hash,''),':',COALESCE(CAST(compiled_definition_json AS CHAR),'')) FROM Workflow ORDER BY workflow_id")){while(rows.next())result.add(rows.getString(1));}
        return result;
    }
}
