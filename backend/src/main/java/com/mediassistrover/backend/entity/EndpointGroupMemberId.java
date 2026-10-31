package com.mediassistrover.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
public class EndpointGroupMemberId implements Serializable {
    @Column(name = "endpoint_group_id", columnDefinition = "BINARY(16)") private UUID endpointGroupId;
    @Column(name = "endpoint_id", columnDefinition = "BINARY(16)") private UUID endpointId;
}
