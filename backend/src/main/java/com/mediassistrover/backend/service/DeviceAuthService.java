package com.mediassistrover.backend.service;

import com.mediassistrover.backend.dto.pi.response.PiResponses.DeviceTokenResponse;

public interface DeviceAuthService {
    DeviceTokenResponse authenticate(String robotCode,String deviceSecret);
}
