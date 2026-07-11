package com.yuuka.backend.common.security;

import com.yuuka.backend.common.config.AuthProperties;
import com.yuuka.backend.common.config.CorsProperties;
import com.yuuka.backend.common.config.JwtProperties;
import com.yuuka.backend.common.config.OwnerProperties;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class StartupSecurityValidator implements ApplicationRunner {
  private static final String DEVELOPMENT_JWT_SECRET = "change-me-to-a-32-byte-minimum-secret";
  private static final String DEVELOPMENT_DATABASE_PASSWORD = "yuuka_dev_password";

  private final Environment environment;
  private final JwtProperties jwtProperties;
  private final CorsProperties corsProperties;
  private final AuthProperties authProperties;
  private final OwnerProperties ownerProperties;
  private final String datasourcePassword;

  public StartupSecurityValidator(
      Environment environment,
      JwtProperties jwtProperties,
      CorsProperties corsProperties,
      AuthProperties authProperties,
      OwnerProperties ownerProperties,
      @Value("${spring.datasource.password:}") String datasourcePassword) {
    this.environment = environment;
    this.jwtProperties = jwtProperties;
    this.corsProperties = corsProperties;
    this.authProperties = authProperties;
    this.ownerProperties = ownerProperties;
    this.datasourcePassword = datasourcePassword;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!isProductionProfile()) {
      return;
    }

    reject(jwtProperties.secret().equals(DEVELOPMENT_JWT_SECRET), "default JWT secret");
    reject(datasourcePassword.equals(DEVELOPMENT_DATABASE_PASSWORD), "default database password");
    reject(datasourcePassword.isBlank(), "blank database password");
    reject(datasourcePassword.length() < 16, "database password shorter than 16 characters");
    reject(authProperties.registrationEnabled(), "public registration in production");
    reject(!ownerProperties.hasEmail(), "missing owner email in production");
    reject(!ownerProperties.hasPasswordHash(), "missing owner password hash in production");
    reject(
        ownerProperties.hasBootstrapPassword(), "plaintext owner bootstrap password in production");
    reject(!ownerProperties.hasTotpSecret(), "missing owner TOTP secret in production");
    reject(
        corsProperties.allowedOrigins().stream().anyMatch(this::isLocalDevelopmentOrigin),
        "localhost CORS origins in production");
    reject(
        corsProperties.allowedOrigins().stream().anyMatch(this::isUnsafeProductionOrigin),
        "wildcard or non-HTTPS CORS origins in production");
  }

  private boolean isProductionProfile() {
    return Arrays.stream(environment.getActiveProfiles()).anyMatch("prod"::equalsIgnoreCase);
  }

  private boolean isLocalDevelopmentOrigin(String origin) {
    return origin.contains("localhost") || origin.contains("127.0.0.1");
  }

  private boolean isUnsafeProductionOrigin(String origin) {
    return "*".equals(origin) || !origin.startsWith("https://");
  }

  private void reject(boolean condition, String reason) {
    if (condition) {
      throw new IllegalStateException("Refusing to start production profile with " + reason);
    }
  }
}
