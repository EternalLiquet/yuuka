package com.yuuka.backend.common.api;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
  private final JdbcTemplate jdbcTemplate;
  private final ApplicationVersion applicationVersion;

  public HealthController(JdbcTemplate jdbcTemplate, ApplicationVersion applicationVersion) {
    this.jdbcTemplate = jdbcTemplate;
    this.applicationVersion = applicationVersion;
  }

  @GetMapping("/health")
  public SystemStatusResponse health() {
    return live();
  }

  @GetMapping("/health/live")
  public SystemStatusResponse live() {
    return new SystemStatusResponse("UP", applicationVersion.version());
  }

  @GetMapping(value = "/health/version", produces = MediaType.APPLICATION_JSON_VALUE)
  public VersionResponse version() {
    return new VersionResponse(applicationVersion.version());
  }

  @GetMapping("/health/ready")
  public HealthResponse readiness() {
    jdbcTemplate.queryForObject("select 1", Integer.class);
    return new HealthResponse("UP");
  }
}
