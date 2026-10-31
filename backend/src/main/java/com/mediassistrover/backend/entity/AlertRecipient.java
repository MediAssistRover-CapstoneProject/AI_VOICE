package com.mediassistrover.backend.entity;

import com.mediassistrover.backend.enums.*;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="AlertRecipient",uniqueConstraints=@UniqueConstraint(name="uq_alert_recipient_channel",columnNames={"alert_id","account_id","channel"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AlertRecipient {
    @Id @GeneratedValue @UuidGenerator @Column(name="alert_recipient_id",columnDefinition="BINARY(16)") private UUID alertRecipientId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="alert_id",nullable=false) private Alert alert;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="account_id",nullable=false) private Account account;
    @Enumerated(EnumType.STRING) @Column(name="channel",nullable=false,length=24) private AlertChannel channel;
    @Column(name="recipient_address_snapshot",length=255) private String recipientAddressSnapshot;
    @Column(name="resolved_role_snapshot",length=64) private String resolvedRoleSnapshot;
    @Enumerated(EnumType.STRING) @Column(name="delivery_status",nullable=false,length=24) private AlertDeliveryStatus deliveryStatus;
    @Column(name="sent_at") private Instant sentAt;
    @Column(name="delivered_at") private Instant deliveredAt;
    @Column(name="acknowledged_at") private Instant acknowledgedAt;
    @Column(name="error_code",length=100) private String errorCode;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
}
