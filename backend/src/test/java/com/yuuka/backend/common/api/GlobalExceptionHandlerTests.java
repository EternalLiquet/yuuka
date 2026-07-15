package com.yuuka.backend.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuuka.backend.common.security.TraceIdFilter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

class GlobalExceptionHandlerTests {
  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void typeMismatchedRequestParametersUseValidationErrorEnvelope() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(TraceIdFilter.ATTRIBUTE, "trace-123");

    var response =
        handler.handleTypeMismatch(
            new MethodArgumentTypeMismatchException("thirty", int.class, "days", null, null),
            request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody())
        .isEqualTo(
            new ApiError(
                "VALIDATION_ERROR",
                "The request could not be completed.",
                Map.of("days", "must be a valid integer"),
                "trace-123"));
  }
}
