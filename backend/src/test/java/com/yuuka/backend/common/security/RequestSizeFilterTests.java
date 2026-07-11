package com.yuuka.backend.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuuka.backend.common.config.HttpProperties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestSizeFilterTests {
  private static final int LIMIT = 1024;
  private final RequestSizeFilter filter = new RequestSizeFilter(new HttpProperties(LIMIT));

  @Test
  void rejectsAChunkedBodyThatExceedsTheLimitWithoutCallingTheApplication() throws Exception {
    MockHttpServletRequest request = unknownLengthRequest();
    request.setMethod("POST");
    request.setContent(new byte[LIMIT + 1]);
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicInteger calls = new AtomicInteger();

    filter.doFilter(
        request, response, (ignoredRequest, ignoredResponse) -> calls.incrementAndGet());

    assertThat(response.getStatus()).isEqualTo(413);
    assertThat(calls).hasValue(0);
  }

  @Test
  void replaysAnAcceptedUnknownLengthBodyToTheApplication() throws Exception {
    MockHttpServletRequest request = unknownLengthRequest();
    request.setMethod("POST");
    request.setContent(new byte[LIMIT]);
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicInteger observedLength = new AtomicInteger();

    filter.doFilter(
        request,
        response,
        (wrapped, ignoredResponse) ->
            observedLength.set(wrapped.getInputStream().readAllBytes().length));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(observedLength).hasValue(LIMIT);
  }

  private MockHttpServletRequest unknownLengthRequest() {
    return new MockHttpServletRequest() {
      @Override
      public long getContentLengthLong() {
        return -1;
      }

      @Override
      public int getContentLength() {
        return -1;
      }
    };
  }
}
