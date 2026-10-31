package com.mediassistrover.backend.config;

import com.mediassistrover.backend.security.RestAccessDeniedHandler;
import com.mediassistrover.backend.security.RestAuthenticationEntryPoint;
import com.mediassistrover.backend.security.JwtAuthenticationFilter;
import com.mediassistrover.backend.security.DeviceAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final DeviceAuthenticationFilter deviceAuthenticationFilter;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> { })
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(authorize -> authorize
                        // Authenticate the initial request; allow internal completion/error dispatches
                        // to preserve the response status when the JWT filter does not run again.
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC,jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .requestMatchers(
                                "/actuator/health",
                                "/v3/api-docs/**",
                                "/swagger-ui.html",
                                "/mapping.html",
                                "/assistant-kiosk.html",
                                "/assistant-staff.html",
                                "/knowledge-admin.html",
                                "/assistant.css",
                                "/favicon.svg",
                                "/swagger-ui/**",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout",
                                "/api/v1/pi/auth/token",
                                "/api/v1/public/**"
                        ).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(deviceAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(jwtAuthenticationFilter, DeviceAuthenticationFilter.class)
                .build();
    }
}
