package com.mediassistrover.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="HeatmapRecord",uniqueConstraints=@UniqueConstraint(name="UQ_HeatmapRecord_window",columnNames={"map_id","heatmap_type","period_start","period_end","cell_size_m"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class HeatmapRecord {
    @Id @GeneratedValue @UuidGenerator @Column(name="heatmap_record_id",columnDefinition="BINARY(16)") private UUID heatmapRecordId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="map_id",nullable=false) private FacilityMap map;
    @Column(name="heatmap_type",nullable=false,length=64) private String heatmapType;
    @Column(name="period_start",nullable=false) private Instant periodStart;
    @Column(name="period_end",nullable=false) private Instant periodEnd;
    @Column(name="cell_size_m",nullable=false,precision=10,scale=4) private BigDecimal cellSizeM;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="grid_data_json",nullable=false,columnDefinition="JSON") private JsonNode gridDataJson;
    @Column(name="image_uri",columnDefinition="TEXT") private String imageUri;
    @Column(name="max_value",precision=14,scale=4) private BigDecimal maxValue;
    @Column(name="source_record_count",nullable=false) private int sourceRecordCount;
    @Column(name="generated_at",nullable=false) private Instant generatedAt;
}
