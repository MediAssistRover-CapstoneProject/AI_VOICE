package com.mediassistrover.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.enums.ZoneType;
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

@Entity @Table(name = "Zone") @EntityListeners(AuditingEntityListener.class)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Zone {
    @Id @GeneratedValue @UuidGenerator @Column(name = "zone_id", columnDefinition = "BINARY(16)") private UUID zoneId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "map_id", nullable = false) private FacilityMap map;
    @Column(name = "zone_code", nullable = false, length = 64) private String zoneCode;
    @Column(name = "zone_name", nullable = false, length = 160) private String zoneName;
    @Enumerated(EnumType.STRING) @Column(name = "zone_type", nullable = false, length = 24) private ZoneType zoneType;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "polygon_json", nullable = false, columnDefinition = "JSON") private JsonNode polygonJson;
    @Column(name = "speed_limit_mps", precision = 5, scale = 3) private BigDecimal speedLimitMps;
    @Column(name = "capacity_limit") private Integer capacityLimit;
    @Column(name = "calibrated_area_m2", precision = 12, scale = 4) private BigDecimal calibratedAreaM2;
    @Column(name = "camera_source_ref", length = 255) private String cameraSourceRef;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "camera_roi_json", columnDefinition = "JSON") private JsonNode cameraRoiJson;
    @Column(name = "is_active", nullable = false) private boolean active;
    @Column(name = "is_public", nullable = false) private boolean publicVisible;
    @Column(name = "description", columnDefinition = "TEXT") private String description;
    @CreatedDate @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @LastModifiedDate @Column(name = "updated_at", nullable = false) private Instant updatedAt;
}
