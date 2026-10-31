package com.mediassistrover.backend.service;

public record RequestMetadata(String ipAddress, String userAgent, String correlationId) {
}
