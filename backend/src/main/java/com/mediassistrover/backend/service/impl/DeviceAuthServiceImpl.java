package com.mediassistrover.backend.service.impl;

import com.mediassistrover.backend.dto.pi.response.PiResponses.DeviceTokenResponse;
import com.mediassistrover.backend.entity.Robot;
import com.mediassistrover.backend.exception.DeviceAuthenticationException;
import com.mediassistrover.backend.repository.RobotRepository;
import com.mediassistrover.backend.security.DeviceJwtTokenService;
import com.mediassistrover.backend.service.DeviceAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class DeviceAuthServiceImpl implements DeviceAuthService {
    private final RobotRepository robotRepository;
    private final PasswordEncoder passwordEncoder;
    private final DeviceJwtTokenService tokenService;
    @Override public DeviceTokenResponse authenticate(String code,String secret){Robot robot=robotRepository.findByRobotCodeIgnoreCase(code).orElseThrow(this::invalid);if(robot.getDeviceSecretHash()==null||!passwordEncoder.matches(secret,robot.getDeviceSecretHash()))throw invalid();var token=tokenService.issue(robot);return new DeviceTokenResponse(token.value(),"Bearer",token.expiresAt(),robot.getRobotId());}
    private DeviceAuthenticationException invalid(){return new DeviceAuthenticationException("Robot code hoặc device secret không hợp lệ");}
}
