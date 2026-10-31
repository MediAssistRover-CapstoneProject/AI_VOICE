package com.mediassistrover.backend.entity;

import com.mediassistrover.backend.enums.SelectionPolicy;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "EndpointGroup") @EntityListeners(AuditingEntityListener.class)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class EndpointGroup {
    @Id @GeneratedValue @UuidGenerator @Column(name = "endpoint_group_id", columnDefinition = "BINARY(16)") private UUID endpointGroupId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "map_id", nullable = false) private FacilityMap map;
    @Column(name = "group_code", nullable = false, length = 64) private String groupCode;
    @Column(name = "group_name", nullable = false, length = 160) private String groupName;
    @Enumerated(EnumType.STRING) @Column(name = "selection_policy", nullable = false, length = 32) private SelectionPolicy selectionPolicy;
    @Column(name = "allow_fallback", nullable = false) private boolean allowFallback;
    @Column(name = "description", columnDefinition = "TEXT") private String description;
    @Column(name = "is_active", nullable = false) private boolean active;
    @CreatedDate @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @LastModifiedDate @Column(name = "updated_at", nullable = false) private Instant updatedAt;
}
