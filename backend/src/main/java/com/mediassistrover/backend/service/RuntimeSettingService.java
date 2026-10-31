package com.mediassistrover.backend.service;

public interface RuntimeSettingService {
    long getLong(String key, long fallback, long minimum, long maximum);
    boolean getBoolean(String key, boolean fallback);
    String getString(String key, String fallback);
}
