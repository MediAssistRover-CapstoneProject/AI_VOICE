package com.mediassistrover.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.BatteryUnit;
import com.mediassistrover.backend.enums.RobotStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "Robot") @EntityListeners(AuditingEntityListener.class)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Robot {
    @Id @GeneratedValue @UuidGenerator @Column(name="robot_id",columnDefinition="BINARY(16)") private UUID robotId;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="current_map_id") private FacilityMap currentMap;
    @Column(name="robot_code",nullable=false,length=64,unique=true) private String robotCode;
    @Column(name="robot_name",nullable=false,length=160) private String robotName;
    @Column(name="model",nullable=false,length=100) private String model;
    @Column(name="serial_number",length=100,unique=true) private String serialNumber;
    @Column(name="runtime_version",length=64) private String runtimeVersion;
    @Column(name="firmware_version",length=64) private String firmwareVersion;
    @Column(name="protocol_version",nullable=false) private int protocolVersion;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="capabilities_json",nullable=false,columnDefinition="JSON") private JsonNode capabilitiesJson;
    @Column(name="deployed_catalog_hash",length=64,columnDefinition="CHAR(64)") private String deployedCatalogHash;
    @Column(name="map_asset_hash",length=64,columnDefinition="CHAR(64)") private String mapAssetHash;
    @Enumerated(EnumType.STRING) @Column(name="status",nullable=false,length=32) private RobotStatus status;
    @Column(name="battery_raw") private Integer batteryRaw;
    @Enumerated(EnumType.STRING) @Column(name="battery_unit",length=32) private BatteryUnit batteryUnit;
    @Column(name="battery_percent",precision=5,scale=2) private BigDecimal batteryPercent;
    @Column(name="battery_captured_at") private Instant batteryCapturedAt;
    @Column(name="last_heartbeat_at") private Instant lastHeartbeatAt;
    @Column(name="commissioned_at") private Instant commissionedAt;
    @Column(name="device_secret_hash",length=100) private String deviceSecretHash;
    @Column(name="device_token_version",nullable=false) private long deviceTokenVersion;
    @Column(name="device_secret_rotated_at") private Instant deviceSecretRotatedAt;
    @Version @Column(name="row_version",nullable=false) private long rowVersion;
    @CreatedDate @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @LastModifiedDate @Column(name="updated_at",nullable=false) private Instant updatedAt;
}
