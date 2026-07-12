package com.yuuka.backend.common.api;

import com.yuuka.backend.auth.application.InvalidRefreshTokenException;
import com.yuuka.backend.common.security.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> handleValidation(
      MethodArgumentNotValidException exception, HttpServletRequest request) {
    Map<String, String> fields = new LinkedHashMap<>();
    exception
        .getBindingResult()
        .getFieldErrors()
        .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
    return response(
        HttpStatus.BAD_REQUEST,
        new ApiError(
            "VALIDATION_ERROR", "The request could not be completed.", fields, traceId(request)));
  }

  @ExceptionHandler(BadCredentialsException.class)
  public ResponseEntity<ApiError> handleBadCredentials(HttpServletRequest request) {
    return response(
        HttpStatus.UNAUTHORIZED,
        ApiError.of("INVALID_CREDENTIALS", "Invalid email or password.", traceId(request)));
  }

  @ExceptionHandler(InvalidRefreshTokenException.class)
  public ResponseEntity<ApiError> handleInvalidRefreshToken(HttpServletRequest request) {
    return response(
        HttpStatus.UNAUTHORIZED,
        ApiError.of("INVALID_REFRESH_TOKEN", "The session is no longer valid.", traceId(request)));
  }

  @ExceptionHandler(ResourceNotFoundException.class)
  public ResponseEntity<ApiError> handleNotFound(HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND,
        ApiError.of("NOT_FOUND", "The requested resource was not found.", traceId(request)));
  }

  @ExceptionHandler(ConflictException.class)
  public ResponseEntity<ApiError> handleConflict(
      ConflictException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT, ApiError.of("CONFLICT", exception.getMessage(), traceId(request)));
  }

  @ExceptionHandler(BusinessRuleException.class)
  public ResponseEntity<ApiError> handleBusinessRule(
      BusinessRuleException exception, HttpServletRequest request) {
    return response(
        HttpStatus.UNPROCESSABLE_ENTITY,
        ApiError.of(
            exception.code(), exception.getMessage(), exception.details(), traceId(request)));
  }

  @ExceptionHandler({ObjectOptimisticLockingFailureException.class})
  public ResponseEntity<ApiError> handleOptimisticLock(HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        ApiError.of(
            "STALE_VERSION",
            "This record changed since it was loaded. Refresh and try again.",
            traceId(request)));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ApiError> handleConstraint(
      DataIntegrityViolationException exception, HttpServletRequest request) {
    LOGGER.warn("Database constraint rejected request traceId={}", traceId(request));
    return response(
        HttpStatus.CONFLICT,
        ApiError.of(
            "CONSTRAINT_VIOLATION", "The change conflicts with current data.", traceId(request)));
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ApiError> handleAccessDenied(HttpServletRequest request) {
    return response(
        HttpStatus.FORBIDDEN,
        ApiError.of("FORBIDDEN", "The request is not permitted.", traceId(request)));
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiError> handleResponseStatus(
      ResponseStatusException exception, HttpServletRequest request) {
    String code = exception.getStatusCode().value() == 429 ? "RATE_LIMITED" : "REQUEST_FAILED";
    String message = exception.getReason() == null ? "The request failed." : exception.getReason();
    return ResponseEntity.status(exception.getStatusCode())
        .body(ApiError.of(code, message, traceId(request)));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleUnexpected(
      Exception exception, HttpServletRequest request) {
    LOGGER.error("Unhandled request failure traceId={}", traceId(request), exception);
    return response(
        HttpStatus.INTERNAL_SERVER_ERROR,
        ApiError.of("INTERNAL_ERROR", "The request could not be completed.", traceId(request)));
  }

  private ResponseEntity<ApiError> response(HttpStatus status, ApiError error) {
    return ResponseEntity.status(status).body(error);
  }

  private String traceId(HttpServletRequest request) {
    Object traceId = request.getAttribute(TraceIdFilter.ATTRIBUTE);
    return traceId == null ? "unknown" : traceId.toString();
  }
}
