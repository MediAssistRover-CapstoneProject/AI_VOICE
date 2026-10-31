package com.mediassistrover.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

/** One temporary map context per robot. A process restart requires a new context. */
@Entity @Table(name="RobotNavigationSession") @Getter @Setter @NoArgsConstructor
public class RobotNavigationSession {
    @Id @Column(name="robot_id",columnDefinition="BINARY(16)") private UUID robotId;
    @Column(name="session_id",nullable=false,unique=true,columnDefinition="BINARY(16)") private UUID sessionId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="map_id",nullable=false) private FacilityMap map;
    @Column(name="map_asset_hash",nullable=false,length=64,columnDefinition="CHAR(64)") private String mapAssetHash;
    @Column(name="producer_session_id",columnDefinition="BINARY(16)") private UUID producerSessionId;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="expires_at",nullable=false) private Instant expiresAt;
    @Column(name="closed_at") private Instant closedAt;
    @Column(name="initialization_mode",nullable=false,length=16) private String initializationMode;
    @Column(name="initialization_revision",nullable=false) private long initializationRevision;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="initial_pose_json",columnDefinition="json") private JsonNode initialPoseJson;
    public boolean open(Instant now){return closedAt==null && expiresAt.isAfter(now);}
}
