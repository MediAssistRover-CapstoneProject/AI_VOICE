package com.mediassistrover.backend.entity;

import com.mediassistrover.backend.enums.EndpointType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "Endpoint") @EntityListeners(AuditingEntityListener.class)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Endpoint {
    @Id @GeneratedValue @UuidGenerator @Column(name = "endpoint_id", columnDefinition = "BINARY(16)") private UUID endpointId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "map_id", nullable = false) private FacilityMap map;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "zone_id") private Zone zone;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "room_id") private Room room;
    @Column(name = "endpoint_code", nullable = false, length = 64) private String endpointCode;
    @Column(name = "endpoint_name", nullable = false, length = 160) private String endpointName;
    @Enumerated(EnumType.STRING) @Column(name = "endpoint_type", nullable = false, length = 32) private EndpointType endpointType;
    @Column(name = "x_m", nullable = false, precision = 12, scale = 4) private BigDecimal xM;
    @Column(name = "y_m", nullable = false, precision = 12, scale = 4) private BigDecimal yM;
    @Column(name = "yaw_rad", nullable = false, precision = 10, scale = 6) private BigDecimal yawRad;
    @Column(name = "is_active", nullable = false) private boolean active;
    @Column(name = "is_accessible", nullable = false) private boolean accessible;
    @Column(name = "is_public", nullable = false) private boolean publicVisible;
    @Column(name = "description", columnDefinition = "TEXT") private String description;
    @CreatedDate @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @LastModifiedDate @Column(name = "updated_at", nullable = false) private Instant updatedAt;
}
