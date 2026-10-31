package com.mediassistrover.backend.entity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="RobotMqttSession") @Getter @Setter @NoArgsConstructor
public class RobotMqttSession {
    @Id @Column(name="robot_id",columnDefinition="BINARY(16)") private UUID robotId;
    @Column(name="transport_session_id",nullable=false,columnDefinition="BINARY(16)") private UUID transportSessionId;
    @Column(name="producer_session_id",nullable=false,columnDefinition="BINARY(16)") private UUID producerSessionId;
    @Column(name="owner_instance_id",nullable=false,columnDefinition="BINARY(16)") private UUID ownerInstanceId;
    @Column(name="connection_id",nullable=false,columnDefinition="BINARY(16)") private UUID connectionId;
    @Column(name="handshake_accepted",nullable=false) private boolean handshakeAccepted;
    @Column(name="handshake_acked",nullable=false) private boolean handshakeAcked;
    @Column(name="dispatch_ready",nullable=false) private boolean dispatchReady;
    @Column(name="closed",nullable=false) private boolean closed;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="hello_json",nullable=false,columnDefinition="JSON") private JsonNode helloJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="heartbeat_json",columnDefinition="JSON") private JsonNode heartbeatJson;
    @Column(name="hello_received_at",nullable=false) private Instant helloReceivedAt;
    @Column(name="last_heartbeat_at") private Instant lastHeartbeatAt;
    @Column(name="failure_reason",length=2000) private String failureReason;
}
