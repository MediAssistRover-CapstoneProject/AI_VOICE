package com.mediassistrover.backend.entity;

import com.mediassistrover.backend.enums.*;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name="NavigationRequest")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class NavigationRequest {
    @Id @Column(name="service_request_id",columnDefinition="BINARY(16)") private java.util.UUID serviceRequestId;
    @MapsId @OneToOne(fetch=FetchType.LAZY) @JoinColumn(name="service_request_id") private ServiceRequest serviceRequest;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="map_id",nullable=false) private FacilityMap map;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="workflow_id") private Workflow workflow;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="room_id") private Room room;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="origin_endpoint_id") private Endpoint originEndpoint;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="destination_endpoint_id") private Endpoint destinationEndpoint;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="destination_group_id") private EndpointGroup destinationGroup;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="resolved_endpoint_id") private Endpoint resolvedEndpoint;
    @Enumerated(EnumType.STRING) @Column(name="target_mode",nullable=false,length=16) private TargetMode targetMode;
    @Enumerated(EnumType.STRING) @Column(name="navigation_mode",nullable=false,length=24) private NavigationMode navigationMode;
    @Column(name="accessibility_need",columnDefinition="TEXT") private String accessibilityNeed;
    @Column(name="passenger_count",nullable=false) private int passengerCount;
    @Column(name="arrival_instruction",columnDefinition="TEXT") private String arrivalInstruction;
}
