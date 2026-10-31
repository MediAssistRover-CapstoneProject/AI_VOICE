package com.mediassistrover.backend.util;

import com.mediassistrover.backend.exception.CorrelationIdFilter;
import com.mediassistrover.backend.service.RequestMetadata;
import jakarta.servlet.http.HttpServletRequest;

public final class RequestMetadataFactory {

    private RequestMetadataFactory() {
    }

    public static RequestMetadata from(HttpServletRequest request) {
        Object correlationId = request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE);
        return new RequestMetadata(
                clientIp(request),
                trimTo(request.getHeader("User-Agent"), 1000),
                correlationId == null ? null : correlationId.toString()
        );
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String value = forwarded == null || forwarded.isBlank()
                ? request.getRemoteAddr()
                : forwarded.split(",", 2)[0].trim();
        return trimTo(value, 45);
    }

    private static String trimTo(String value, int maxLength) {
        return value == null ? null : value.substring(0, Math.min(value.length(), maxLength));
    }
}
