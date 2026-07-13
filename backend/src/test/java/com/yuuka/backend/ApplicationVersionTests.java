package com.yuuka.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuuka.backend.common.api.ApplicationVersion;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;

class ApplicationVersionTests {
  @Test
  void returnsDeterministicDevelopmentVersionWhenBuildMetadataIsMissing() {
    ApplicationVersion version = new ApplicationVersion(Optional.empty());

    assertThat(version.version()).isEqualTo("0.0.0-dev");
  }

  @Test
  void returnsBuildMetadataVersionWhenPresent() {
    Properties properties = new Properties();
    properties.setProperty("version", "1.2.3");

    ApplicationVersion version =
        new ApplicationVersion(Optional.of(new BuildProperties(properties)));

    assertThat(version.version()).isEqualTo("1.2.3");
  }

  @Test
  void blankBuildMetadataFallsBackToDevelopmentVersion() {
    Properties properties = new Properties();
    properties.setProperty("version", " ");

    ApplicationVersion version =
        new ApplicationVersion(Optional.of(new BuildProperties(properties)));

    assertThat(version.version()).isEqualTo("0.0.0-dev");
  }
}
