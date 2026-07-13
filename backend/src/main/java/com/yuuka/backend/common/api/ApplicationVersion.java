package com.yuuka.backend.common.api;

import java.util.Optional;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

@Component
public class ApplicationVersion {
  public static final String DEVELOPMENT_VERSION = "0.0.0-dev";

  private final String version;

  public ApplicationVersion(Optional<BuildProperties> buildProperties) {
    this.version =
        buildProperties
            .map(BuildProperties::getVersion)
            .filter(value -> !value.isBlank())
            .orElse(DEVELOPMENT_VERSION);
  }

  public String version() {
    return version;
  }
}
