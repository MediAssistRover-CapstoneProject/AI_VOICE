package com.mediassistrover.backend.entity;

import com.mediassistrover.backend.enums.*;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity @Table(name="EscortRequest")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class EscortRequest {
    @Id @Column(name="service_request_id",columnDefinition="BINARY(16)") private java.util.UUID serviceRequestId;
    @MapsId @OneToOne(fetch=FetchType.LAZY) @JoinColumn(name="service_request_id") private ServiceRequest serviceRequest;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="map_id",nullable=false) private FacilityMap map;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="origin_room_id") private Room originRoom;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="destination_room_id") private Room destinationRoom;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="origin_endpoint_id") private Endpoint originEndpoint;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="destination_endpoint_id") private Endpoint destinationEndpoint;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="destination_group_id") private EndpointGroup destinationGroup;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="resolved_endpoint_id") private Endpoint resolvedEndpoint;
    @Enumerated(EnumType.STRING) @Column(name="target_mode",nullable=false,length=16) private TargetMode targetMode;
    @Column(name="patient_reference",length=100) private String patientReference;
    @Enumerated(EnumType.STRING) @Column(name="escort_mode",nullable=false,length=24) private EscortMode escortMode;
    @Column(name="walking_speed_mps",nullable=false,precision=5,scale=3) private BigDecimal walkingSpeedMps;
    @Column(name="confirmation_timeout_sec",nullable=false) private int confirmationTimeoutSec;
    @Column(name="escort_note",columnDefinition="TEXT") private String escortNote;
}
