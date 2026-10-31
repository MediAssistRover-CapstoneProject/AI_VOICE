package com.mediassistrover.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="RobotNavigationProfile") @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class RobotNavigationProfile {
    @Id @Column(name="robot_id",columnDefinition="BINARY(16)") private UUID robotId;
    @Column(name="map_id",nullable=false,columnDefinition="BINARY(16)") private UUID mapId;
    @Column(name="transport_session_id",nullable=false,columnDefinition="BINARY(16)") private UUID transportSessionId;
    @Column(name="producer_session_id",nullable=false,columnDefinition="BINARY(16)") private UUID producerSessionId;
    @Column(name="device_token_version",nullable=false) private long deviceTokenVersion;
    @Column(name="profile_hash",nullable=false,length=64,columnDefinition="CHAR(64)") private String profileHash;
    @Column(name="map_asset_hash",nullable=false,length=64,columnDefinition="CHAR(64)") private String mapAssetHash;
    @Column(name="acknowledged_at",nullable=false) private Instant acknowledgedAt;
}
