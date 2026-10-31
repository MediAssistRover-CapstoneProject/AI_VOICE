package com.mediassistrover.backend.entity;

import com.mediassistrover.backend.enums.MapStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "Map") @EntityListeners(AuditingEntityListener.class)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class FacilityMap {
    @Id @GeneratedValue @UuidGenerator @Column(name = "map_id", columnDefinition = "BINARY(16)") private UUID mapId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "floor_id", nullable = false) private Floor floor;
    @Column(name = "map_code", nullable = false, length = 64) private String mapCode;
    @Column(name = "version_no", nullable = false) private int versionNo;
    @Column(name = "map_name", nullable = false, length = 160) private String mapName;
    @Column(name = "width_m", nullable = false, precision = 12, scale = 4) private BigDecimal widthM;
    @Column(name = "height_m", nullable = false, precision = 12, scale = 4) private BigDecimal heightM;
    @Column(name = "resolution_m_per_pixel", nullable = false, precision = 10, scale = 6) private BigDecimal resolutionMPerPixel;
    @Column(name = "origin_x_m", nullable = false, precision = 12, scale = 4) private BigDecimal originXM;
    @Column(name = "origin_y_m", nullable = false, precision = 12, scale = 4) private BigDecimal originYM;
    @Column(name = "origin_yaw_rad", nullable = false, precision = 10, scale = 6) private BigDecimal originYawRad;
    @Column(name = "frame_id", nullable = false, length = 64) private String frameId;
    @Column(name = "map_file_uri", nullable = false, columnDefinition = "TEXT") private String mapFileUri;
    @Column(name = "asset_hash", nullable = false, length = 64, columnDefinition = "CHAR(64)") private String assetHash;
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 20) private MapStatus status;
    @Column(name = "commissioned_at") private Instant commissionedAt;
    @CreatedDate @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @LastModifiedDate @Column(name = "updated_at", nullable = false) private Instant updatedAt;
}
