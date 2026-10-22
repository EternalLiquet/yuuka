package com.yuuka.backend.mcp.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.yuuka.backend.mcp.security.McpTokenDecoder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Test-only synthetic MCP tokens and decoder. Uses an RSA key pair generated at test time; the
 * issuer and audience are reserved invalid hostnames. Lives under src/test and is never part of the
 * production artifact.
 */
public final class SyntheticMcpTokens {
  public static final String ISSUER = "https://issuer.test.invalid";
  public static final String RESOURCE_URI = "https://yuuka.test.invalid/mcp";
  public static final String READ_SCOPE = "yuuka:budget:read";

  private static final RSAKey KEY = generate();

  private SyntheticMcpTokens() {}

  private static RSAKey generate() {
    try {
      return new RSAKeyGenerator(2048).keyID("synthetic-test-key").generate();
    } catch (JOSEException exception) {
      throw new IllegalStateException(exception);
    }
  }

  /** The approved-decoder stand-in: RS256, issuer, audience and time checks against the clock. */
  public static McpTokenDecoder decoder(Clock clock) {
    try {
      NimbusJwtDecoder nimbus = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
      JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
      timestamps.setClock(clock);
      OAuth2TokenValidator<Jwt> audience =
          new JwtClaimValidator<List<String>>(
              "aud", aud -> aud != null && aud.contains(RESOURCE_URI));
      nimbus.setJwtValidator(
          new DelegatingOAuth2TokenValidator<>(
              timestamps, new JwtIssuerValidator(ISSUER), audience));
      return nimbus::decode;
    } catch (JOSEException exception) {
      throw new IllegalStateException(exception);
    }
  }

  public static Spec spec(String subject) {
    return new Spec(subject);
  }

  /** Mutable token description; defaults produce a valid read token. */
  public static final class Spec {
    private final String subject;
    private String issuer = ISSUER;
    private String audience = RESOURCE_URI;
    private String scope = READ_SCOPE;
    private Instant issuedAt = Instant.now().minusSeconds(30);
    private Instant expiresAt = Instant.now().plusSeconds(600);
    private Instant notBefore;

    private Spec(String subject) {
      this.subject = subject;
    }

    public Spec issuer(String value) {
      this.issuer = value;
      return this;
    }

    public Spec audience(String value) {
      this.audience = value;
      return this;
    }

    public Spec scope(String value) {
      this.scope = value;
      return this;
    }

    public Spec expiresAt(Instant value) {
      this.expiresAt = value;
      return this;
    }

    public Spec notBefore(Instant value) {
      this.notBefore = value;
      return this;
    }

    public String build() {
      JWTClaimsSet.Builder claims =
          new JWTClaimsSet.Builder()
              .issuer(issuer)
              .subject(subject)
              .audience(audience)
              .issueTime(Date.from(issuedAt))
              .expirationTime(Date.from(expiresAt))
              .jwtID(UUID.randomUUID().toString());
      if (scope != null) {
        claims.claim("scope", scope);
      }
      if (notBefore != null) {
        claims.notBeforeTime(Date.from(notBefore));
      }
      try {
        SignedJWT jwt =
            new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),
                claims.build());
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
      } catch (JOSEException exception) {
        throw new IllegalStateException(exception);
      }
    }
  }
}
