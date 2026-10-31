package com.mediassistrover.backend.entity;
import jakarta.persistence.*;
import lombok.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="MapArtifact") @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class MapArtifact {
    @Id @Column(name="artifact_id",columnDefinition="BINARY(16)") private UUID artifactId;
    @Column(name="session_id",nullable=false,columnDefinition="BINARY(16)",unique=true) private UUID sessionId;
    @Column(name="archive_hash",nullable=false,length=64,columnDefinition="CHAR(64)") private String archiveHash;
    @Column(name="asset_hash",nullable=false,length=64,columnDefinition="CHAR(64)") private String assetHash;
    @Column(name="archive_size",nullable=false) private long archiveSize;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="manifest_json",nullable=false,columnDefinition="JSON") private JsonNode manifestJson;
    @Column(name="map_id",columnDefinition="BINARY(16)",unique=true) private UUID mapId;
    @Column(name="stored_at",nullable=false) private Instant storedAt;
}
