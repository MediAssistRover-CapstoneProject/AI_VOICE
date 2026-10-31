package com.mediassistrover.backend.dto.setting.request;

import com.mediassistrover.backend.enums.SystemSettingDataType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

public final class SystemSettingRequests {
    private SystemSettingRequests() { }

    @Schema(name="SystemSettingCreateRequest") public record Create(
            @NotBlank @Size(max = 100)
            @Pattern(regexp = "[a-z0-9]+(?:[._-][a-z0-9]+)*") String settingKey,
            @NotEmpty Map<String, Object> settingValue,
            @NotNull SystemSettingDataType dataType,
            @NotBlank @Size(max = 64) String category,
            @Size(max = 4000) String description,
            boolean publicSetting
    ) { }

    @Schema(name="SystemSettingUpdateRequest") public record Update(
            @NotEmpty Map<String, Object> settingValue,
            @NotNull SystemSettingDataType dataType,
            @NotBlank @Size(max = 64) String category,
            @Size(max = 4000) String description,
            boolean publicSetting
    ) { }
}
