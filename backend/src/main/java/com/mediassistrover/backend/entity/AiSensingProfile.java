package com.mediassistrover.backend.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="AiSensingProfile") @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AiSensingProfile {
    @Id @Column(name="profile_id",columnDefinition="BINARY(16)") private UUID profileId;
    @Column(name="robot_id",nullable=false,columnDefinition="BINARY(16)") private UUID robotId;
    @Column(name="zone_id",nullable=false,columnDefinition="BINARY(16)") private UUID zoneId;
    @Column(name="sensing_type",nullable=false,length=32) private String sensingType;
    @Column(name="model_version",nullable=false,length=100) private String modelVersion;
    @Column(name="producer_ref",nullable=false,length=255) private String producerRef;
    @Column(name="map_asset_hash",nullable=false,length=64,columnDefinition="CHAR(64)") private String mapAssetHash;
    @Column(name="frame_id",nullable=false,length=64) private String frameId;
    @Column(name="enabled",nullable=false) private boolean enabled;
    @Column(name="person_positions_enabled",nullable=false) private boolean personPositionsEnabled;
    @Column(name="commissioning_note",nullable=false,length=1000) private String commissioningNote;
    @Column(name="configured_by",nullable=false,columnDefinition="BINARY(16)") private UUID configuredBy;
    @Column(name="configured_at",nullable=false) private Instant configuredAt;
}
