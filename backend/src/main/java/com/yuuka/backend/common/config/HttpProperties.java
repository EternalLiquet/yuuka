package com.yuuka.backend.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yuuka.http")
public record HttpProperties(long maxRequestBodySizeBytes) {
  public HttpProperties {
    if (maxRequestBodySizeBytes < 1024) {
      throw new IllegalStateException(
          "yuuka.http.max-request-body-size-bytes must be at least 1024");
    }
  }
}
