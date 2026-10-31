package com.mediassistrover.backend.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(enumAsRef = true)
public enum EndpointType {
    WAYPOINT,
    CHARGER,
    PARK,
    STAGING
}
