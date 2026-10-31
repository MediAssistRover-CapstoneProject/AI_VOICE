package com.mediassistrover.backend.entity;

import com.mediassistrover.backend.enums.TargetMode;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="DeliveryRequest")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class DeliveryRequest {
    @Id @Column(name="service_request_id",columnDefinition="BINARY(16)") private UUID serviceRequestId;
    @MapsId @OneToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="service_request_id") private ServiceRequest serviceRequest;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="map_id",nullable=false) private FacilityMap map;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="pickup_room_id") private Room pickupRoom;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="delivery_room_id") private Room deliveryRoom;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="pickup_endpoint_id",nullable=false) private Endpoint pickupEndpoint;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="delivery_endpoint_id") private Endpoint deliveryEndpoint;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="delivery_group_id") private EndpointGroup deliveryGroup;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="resolved_delivery_endpoint_id") private Endpoint resolvedDeliveryEndpoint;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="sender_account_id") private Account senderAccount;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="receiver_account_id") private Account receiverAccount;
    @Enumerated(EnumType.STRING) @Column(name="target_mode",nullable=false,length=16) private TargetMode targetMode;
    @Column(name="recipient_name",nullable=false,length=160) private String recipientName;
    @Column(name="recipient_contact",length=100) private String recipientContact;
    @Column(name="requires_confirmation",nullable=false) private boolean requiresConfirmation;
    @Column(name="temperature_requirement",columnDefinition="TEXT") private String temperatureRequirement;
    @Column(name="delivery_deadline") private Instant deliveryDeadline;
    @Column(name="picked_up_at") private Instant pickedUpAt;
    @Column(name="delivered_at") private Instant deliveredAt;
    @Column(name="pickup_compartment_confirmed_at") private Instant pickupCompartmentConfirmedAt;
    @Column(name="delivery_compartment_confirmed_at") private Instant deliveryCompartmentConfirmedAt;
    @Column(name="pickup_payload_confirmed_at") private Instant pickupPayloadConfirmedAt;
    @Column(name="delivery_payload_confirmed_at") private Instant deliveryPayloadConfirmedAt;
    @Column(name="delivery_note",columnDefinition="TEXT") private String deliveryNote;
    @Version @Column(name="row_version",nullable=false) private long rowVersion;
}
