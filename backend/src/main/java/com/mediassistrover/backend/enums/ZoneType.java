package com.mediassistrover.backend.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(enumAsRef = true)
public enum ZoneType {
    KEEP_OUT,
    SPEED,
    NO_STOP
}
