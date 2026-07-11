package com.yuuka.backend.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuuka.backend.common.api.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {
  private final ObjectMapper objectMapper;

  public ApiAccessDeniedHandler(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws IOException {
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(
        response.getOutputStream(),
        ApiError.of("FORBIDDEN", "The request is not permitted.", traceId(request)));
  }

  private String traceId(HttpServletRequest request) {
    Object value = request.getAttribute(TraceIdFilter.ATTRIBUTE);
    return value == null ? "unknown" : value.toString();
  }
}
