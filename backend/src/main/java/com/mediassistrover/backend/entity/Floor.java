package com.mediassistrover.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "Floor") @EntityListeners(AuditingEntityListener.class)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Floor {
    @Id @GeneratedValue @UuidGenerator @Column(name = "floor_id", columnDefinition = "BINARY(16)") private UUID floorId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_information_id", nullable = false) private HospitalInformation hospitalInformation;
    @Column(name = "floor_code", nullable = false, length = 64) private String floorCode;
    @Column(name = "floor_name", nullable = false, length = 160) private String floorName;
    @Column(name = "level_no") private Integer levelNo;
    @Column(name = "display_order", nullable = false) private int displayOrder;
    @Column(name = "description", columnDefinition = "TEXT") private String description;
    @Column(name = "is_active", nullable = false) private boolean active;
    @CreatedDate @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @LastModifiedDate @Column(name = "updated_at", nullable = false) private Instant updatedAt;
}
