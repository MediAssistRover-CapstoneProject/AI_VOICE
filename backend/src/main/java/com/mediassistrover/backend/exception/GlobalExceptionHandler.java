package com.mediassistrover.backend.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    ResponseEntity<ApiErrorResponse> handleBinding(Exception exception, HttpServletRequest request) {
        var bindingResult = exception instanceof MethodArgumentNotValidException methodException
                ? methodException.getBindingResult()
                : ((BindException) exception).getBindingResult();
        List<ApiErrorResponse.FieldViolation> violations = bindingResult.getFieldErrors().stream()
                .map(error -> new ApiErrorResponse.FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Dữ liệu không hợp lệ", request, violations);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        List<ApiErrorResponse.FieldViolation> violations = exception.getConstraintViolations().stream()
                .map(violation -> new ApiErrorResponse.FieldViolation(
                        violation.getPropertyPath().toString(), violation.getMessage()))
                .toList();
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Dữ liệu không hợp lệ", request, violations);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleNotFound(ResourceNotFoundException exception, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception.getMessage(), request, null);
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<ApiErrorResponse> handleMissingEndpoint(Exception exception,HttpServletRequest request){
        return response(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","API endpoint not found",request,null);
    }
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> handleMethodNotSupported(Exception exception,HttpServletRequest request){
        return response(HttpStatus.METHOD_NOT_ALLOWED,"METHOD_NOT_ALLOWED",
                "HTTP method is not supported for this endpoint",request,null);
    }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> handleUnreadableBody(Exception exception,HttpServletRequest request){
        return response(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Invalid JSON or enum value",request,null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiErrorResponse> handleInvalidRequestParameter(
            MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        var violation = new ApiErrorResponse.FieldViolation(
                exception.getName(), "Unsupported value: " + exception.getValue());
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Invalid request parameter or enum value", request, List.of(violation));
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ApiErrorResponse> handleConflict(ConflictException exception, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "CONFLICT", exception.getMessage(), request, null);
    }

    @ExceptionHandler(LegacyExecutionRetiredException.class)
    ResponseEntity<ApiErrorResponse> handleLegacyRetired(LegacyExecutionRetiredException exception, HttpServletRequest request) {
        return response(HttpStatus.GONE, "LEGACY_EXECUTION_RETIRED", exception.getMessage(), request, null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiErrorResponse> handleDataIntegrity(
            DataIntegrityViolationException exception, HttpServletRequest request) {
        log.warn("Data integrity conflict, correlationId={}", correlationId(request));
        return response(HttpStatus.CONFLICT, "DATA_INTEGRITY_CONFLICT",
                "Dữ liệu bị trùng hoặc vẫn đang được tham chiếu", request, null);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiErrorResponse> handleOptimisticLock(
            ObjectOptimisticLockingFailureException exception, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "CONCURRENT_UPDATE",
                "Dữ liệu đã được cập nhật bởi yêu cầu khác; vui lòng tải lại", request, null);
    }

    @ExceptionHandler(BusinessRuleException.class)
    ResponseEntity<ApiErrorResponse> handleBusinessRule(BusinessRuleException exception, HttpServletRequest request) {
        return response(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION", exception.getMessage(), request, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", "Bạn không có quyền thực hiện thao tác này", request, null);
    }

    @ExceptionHandler({AuthenticationFailedException.class, InvalidTokenException.class, DeviceAuthenticationException.class})
    ResponseEntity<ApiErrorResponse> handleUnauthorized(RuntimeException exception, HttpServletRequest request) {
        return response(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", exception.getMessage(), request, null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled exception, correlationId={}", correlationId(request), exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Hệ thống không thể xử lý yêu cầu", request, null);
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request,
            List<ApiErrorResponse.FieldViolation> violations
    ) {
        return ResponseEntity.status(status).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(new ApiErrorResponse(
                Instant.now(clock),
                status.value(),
                code,
                message,
                request.getRequestURI(),
                correlationId(request),
                violations
        ));
    }

    private String correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE);
        return value == null ? null : value.toString();
    }
}
