package com.mediassistrover.backend.service.impl;

import com.mediassistrover.backend.entity.SystemSetting;
import com.mediassistrover.backend.repository.SystemSettingRepository;
import com.mediassistrover.backend.service.RuntimeSettingService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RuntimeSettingServiceImpl implements RuntimeSettingService {
    private static final Logger log = LoggerFactory.getLogger(RuntimeSettingServiceImpl.class);
    private final SystemSettingRepository repository;

    @Override
    public long getLong(String key, long fallback, long minimum, long maximum) {
        return repository.findBySettingKey(key).map(SystemSetting::getSettingValue)
                .map(value -> value.get("value"))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::longValue)
                .filter(value -> value >= minimum && value <= maximum)
                .orElseGet(() -> {
                    log.debug("Using property fallback {} for missing or invalid runtime setting {}", fallback, key);
                    return fallback;
                });
    }

    @Override
    public boolean getBoolean(String key, boolean fallback) {
        return repository.findBySettingKey(key).map(SystemSetting::getSettingValue)
                .map(value -> value.get("value"))
                .filter(Boolean.class::isInstance)
                .map(Boolean.class::cast)
                .orElse(fallback);
    }

    @Override
    public String getString(String key, String fallback) {
        return repository.findBySettingKey(key).map(SystemSetting::getSettingValue)
                .map(value -> value.get("value"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(value -> !value.isBlank())
                .orElse(fallback);
    }
}
