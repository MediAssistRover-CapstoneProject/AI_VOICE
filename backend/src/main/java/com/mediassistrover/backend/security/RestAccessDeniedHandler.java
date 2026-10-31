package com.mediassistrover.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.exception.ApiErrorResponse;
import com.mediassistrover.backend.exception.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Object correlationId = request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE);
        boolean passwordChangeRequired = "Password change is required".equals(accessDeniedException.getMessage());
        objectMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(
                Instant.now(clock),
                HttpServletResponse.SC_FORBIDDEN,
                passwordChangeRequired ? "PASSWORD_CHANGE_REQUIRED" : "FORBIDDEN",
                passwordChangeRequired ? "Bạn phải đổi mật khẩu tạm trước khi sử dụng hệ thống" : "Bạn không có quyền thực hiện thao tác này",
                request.getRequestURI(),
                correlationId == null ? null : correlationId.toString(),
                null
        ));
    }
}
