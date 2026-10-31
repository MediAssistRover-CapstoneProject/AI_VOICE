package com.mediassistrover.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity @Table(name = "EndpointGroupMember") @EntityListeners(AuditingEntityListener.class)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class EndpointGroupMember {
    @EmbeddedId private EndpointGroupMemberId id;
    @MapsId("endpointGroupId") @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "endpoint_group_id", nullable = false) private EndpointGroup endpointGroup;
    @MapsId("endpointId") @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "endpoint_id", nullable = false) private Endpoint endpoint;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "map_id", nullable = false) private FacilityMap map;
    @Column(name = "sequence_no", nullable = false) private int sequenceNo;
    @Column(name = "is_active", nullable = false) private boolean active;
    @CreatedDate @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
}
