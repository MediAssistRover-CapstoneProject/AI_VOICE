package com.mediassistrover.backend.vision;

import com.mediassistrover.backend.controller.pi.PiVisionController;
import com.mediassistrover.backend.security.DevicePrincipal;
import com.mediassistrover.backend.vision.VisionDtos.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Tests the actual method-security proxy without starting a database or MQTT. */
class PiVisionControllerSecurityTest {
    private AnnotationConfigApplicationContext context;
    private PiVisionController controller;
    private VisionEventService service;

    @Configuration @EnableMethodSecurity
    static class Config {
        @Bean VisionEventService visionService() { return mock(VisionEventService.class); }
        @Bean PiVisionController visionController(VisionEventService service) { return new PiVisionController(service); }
    }

    @BeforeEach void setup() {
        SecurityContextHolder.clearContext();
        context=new AnnotationConfigApplicationContext(Config.class);
        controller=context.getBean(PiVisionController.class);
        service=context.getBean(VisionEventService.class);
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); context.close(); }

    @Test void anonymousCannotCallVisionIngestion() {
        assertThatThrownBy(() -> controller.detection(null,null))
                .isInstanceOfAny(AuthenticationCredentialsNotFoundException.class,AccessDeniedException.class);
        verifyNoInteractions(service);
    }

    @Test void staffTokenCannotImpersonateDevice() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "staff","unused",List.of(new SimpleGrantedAuthority("ROLE_STAFF"))));
        assertThatThrownBy(() -> controller.detection(null,null)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(service);
    }

    @Test void deviceAuthorityCanReachService() {
        var principal=new DevicePrincipal(UUID.randomUUID(),"MAR-TEST",0,"test",Instant.now().plusSeconds(60));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal,"unused",principal.authorities()));
        var expected=new DetectionReceipt(UUID.randomUUID(),true,false,true,true,null,null,Instant.now());
        when(service.record(null,principal)).thenReturn(expected);
        assertThat(controller.detection(null,principal)).isSameAs(expected);
        verify(service).record(null,principal);
    }
}
