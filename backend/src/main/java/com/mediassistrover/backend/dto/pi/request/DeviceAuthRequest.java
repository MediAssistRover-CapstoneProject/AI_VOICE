package com.mediassistrover.backend.dto.pi.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeviceAuthRequest(@NotBlank @Size(max=64) String robotCode,
                                @NotBlank @Size(max=200) String deviceSecret) { }
