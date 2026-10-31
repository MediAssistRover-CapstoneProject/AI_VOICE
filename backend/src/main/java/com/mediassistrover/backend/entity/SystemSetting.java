package com.mediassistrover.backend.entity;

import com.mediassistrover.backend.enums.SystemSettingDataType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "SystemSetting")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemSetting {
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "system_setting_id", columnDefinition = "BINARY(16)")
    private UUID systemSettingId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by_account_id")
    private Account updatedBy;

    @Column(name = "setting_key", nullable = false, length = 100, unique = true)
    private String settingKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "setting_value_json", nullable = false, columnDefinition = "JSON")
    private Map<String, Object> settingValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_type", nullable = false, length = 20)
    private SystemSettingDataType dataType;

    @Column(name = "category", nullable = false, length = 64)
    private String category;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_public", nullable = false)
    private boolean publicSetting;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
