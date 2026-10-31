package com.mediassistrover.backend.dto.setting.response;

import com.mediassistrover.backend.enums.SystemSettingDataType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record SystemSettingResponse(
        UUID systemSettingId,
        String settingKey,
        Map<String, Object> settingValue,
        SystemSettingDataType dataType,
        String category,
        String description,
        boolean publicSetting,
        UUID updatedByAccountId,
        Instant updatedAt
) { }
