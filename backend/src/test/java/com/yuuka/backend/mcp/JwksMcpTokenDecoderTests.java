package com.yuuka.backend.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.RSAKey;
import com.yuuka.backend.mcp.security.JwksMcpTokenDecoder;
import com.yuuka.backend.mcp.support.JwtMinter;
import com.yuuka.backend.mcp.support.StubJwksServer;
import com.yuuka.backend.mcp.support.SyntheticMcpTokens;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Production decoder against a loopback stub JWKS. All keys are generated at test time. The JWK
 * cache lifetime (Nimbus default, 5 minutes of wall-clock time) cannot be advanced here, so expiry
 * by TTL alone is deliberately not asserted; refresh is exercised through the unknown-kid path.
 */
class JwksMcpTokenDecoderTests {
  private static final String ISSUER = SyntheticMcpTokens.ISSUER;
  private static final String RESOURCE = SyntheticMcpTokens.RESOURCE_URI;
  private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private StubJwksServer jwks;
  private JwksMcpTokenDecoder decoder;
  private RSAKey k1;

  @BeforeEach
  void start() {
    jwks = new StubJwksServer();
    k1 = JwtMinter.rsa("k1");
    jwks.publish(k1.toPublicJWK());
    decoder =
        new JwksMcpTokenDecoder(ISSUER, jwks.jwkSetUri(), RESOURCE, Duration.ofSeconds(60), CLOCK);
  }

  @AfterEach
  void stop() {
    jwks.close();
  }

  private String token(RSAKey key, String issuer, String audience, Instant exp, Instant nbf) {
    return JwtMinter.sign(
        key, JWSAlgorithm.RS256, null, JwtMinter.claims(issuer, audience, exp, nbf).build());
  }

  private String valid(RSAKey key) {
    return token(key, ISSUER, RESOURCE, NOW.plusSeconds(600), null);
  }

  @Test
  void fetchesLazilyOnFirstTokenOnly() {
    assertThat(jwks.fetchCount()).isZero();
    assertThat(decoder.decode(valid(k1)).getSubject()).isEqualTo("subject-1");
    decoder.decode(valid(k1));
    assertThat(jwks.fetchCount()).isEqualTo(1);
  }

  @Test
  void acceptsValidRs256EcAndAtJwtTokens() {
    ECKey ec = JwtMinter.ec("ec1");
    jwks.publish(k1.toPublicJWK(), ec.toPublicJWK());
    Jwt rs = decoder.decode(valid(k1));
    assertThat(rs.getClaimAsString("scope")).isEqualTo("yuuka:budget:read");
    String es =
        JwtMinter.sign(
            ec,
            JWSAlgorithm.ES256,
            null,
            JwtMinter.claims(ISSUER, RESOURCE, NOW.plusSeconds(600), null).build());
    assertThat(decoder.decode(es)).isNotNull();
    String atJwt =
        JwtMinter.sign(
            k1,
            JWSAlgorithm.RS256,
            new JOSEObjectType("at+jwt"),
            JwtMinter.claims(ISSUER, RESOURCE, NOW.plusSeconds(600), null).build());
    assertThat(decoder.decode(atJwt)).isNotNull();
  }

  @Test
  void rejectsWrongIssuerWrongAudienceAndMissingExpiry() {
    assertRejected(token(k1, "https://other.test.invalid", RESOURCE, NOW.plusSeconds(600), null));
    assertRejected(token(k1, ISSUER, "https://other.test.invalid/mcp", NOW.plusSeconds(600), null));
    assertRejected(token(k1, ISSUER, RESOURCE, null, null));
  }

  @Test
  void rejectsHs256SignedWithOwnerSecretAndAlgNone() {
    byte[] ownerSecret = "change-me-to-a-32-byte-minimum-secret".getBytes(StandardCharsets.UTF_8);
    var claims = JwtMinter.claims(ISSUER, RESOURCE, NOW.plusSeconds(600), null).build();
    assertRejected(JwtMinter.hs256(ownerSecret, "k1", claims));
    assertRejected(JwtMinter.none(claims));
  }

  @Test
  void rejectsSignatureFromAnUnpublishedKeyWithKnownKid() {
    RSAKey impostor = JwtMinter.rsa("k1");
    assertRejected(valid(impostor));
  }

  @Test
  void expiryIsEnforcedPastTheExplicitSkewAndToleratedWithin() {
    assertThat(decoder.decode(token(k1, ISSUER, RESOURCE, NOW.minusSeconds(30), null))).isNotNull();
    assertRejected(token(k1, ISSUER, RESOURCE, NOW.minusSeconds(120), null));
  }

  @Test
  void notBeforeIsEnforcedPastTheExplicitSkewAndToleratedWithin() {
    assertThat(
            decoder.decode(token(k1, ISSUER, RESOURCE, NOW.plusSeconds(600), NOW.plusSeconds(30))))
        .isNotNull();
    assertRejected(token(k1, ISSUER, RESOURCE, NOW.plusSeconds(600), NOW.plusSeconds(300)));
  }

  @Test
  void zeroSkewRejectsAnyExpiredToken() {
    JwksMcpTokenDecoder strict =
        new JwksMcpTokenDecoder(ISSUER, jwks.jwkSetUri(), RESOURCE, Duration.ZERO, CLOCK);
    assertThatThrownBy(() -> strict.decode(token(k1, ISSUER, RESOURCE, NOW.minusSeconds(5), null)))
        .isInstanceOf(JwtException.class);
  }

  /**
   * ADR 4.3(b): rotation is key management, not revocation. Observed behavior of Spring Security
   * 6.5 / Nimbus with its default cache: the JWK set stays cached for its TTL (5 minutes,
   * wall-clock, not injectable); a token with an unknown kid forces an immediate re-fetch.
   */
  @Test
  void keyRotationAgainstAWarmedDecoder() {
    // Warm the cache with K1 only.
    assertThat(decoder.decode(valid(k1))).isNotNull();
    assertThat(jwks.fetchCount()).isEqualTo(1);

    // Provider publishes K2 alongside K1. K1 tokens still verify from the cache (no fetch).
    RSAKey k2 = JwtMinter.rsa("k2");
    jwks.publish(k1.toPublicJWK(), k2.toPublicJWK());
    assertThat(decoder.decode(valid(k1))).isNotNull();
    assertThat(jwks.fetchCount()).isEqualTo(1);
    // A K2 token has an unknown kid: the decoder re-fetches and accepts it.
    assertThat(decoder.decode(valid(k2))).isNotNull();
    assertThat(jwks.fetchCount()).isEqualTo(2);

    // Provider removes K1 early. Within the cache window the cached set still contains K1, so a K1
    // token is still accepted: this is the documented window, not a revocation mechanism.
    jwks.publish(k2.toPublicJWK());
    assertThat(decoder.decode(valid(k1))).isNotNull();
    assertThat(decoder.decode(valid(k2))).isNotNull();
    assertThat(jwks.fetchCount()).isEqualTo(2);

    // Any unknown kid triggers a refresh (as cache expiry would). The refreshed set no longer
    // contains K1, so K1 tokens are rejected from then on while K2 keeps working.
    assertRejected(valid(JwtMinter.rsa("k3")));
    assertThat(jwks.fetchCount()).isEqualTo(3);
    assertRejected(valid(k1));
    assertThat(decoder.decode(valid(k2))).isNotNull();
  }

  @Test
  void unreachableJwksDoesNotFailConstructionAndRejectsTokens() {
    jwks.close();
    JwksMcpTokenDecoder offline =
        new JwksMcpTokenDecoder(ISSUER, jwks.jwkSetUri(), RESOURCE, Duration.ofSeconds(60), CLOCK);
    assertThatThrownBy(() -> offline.decode(valid(k1))).isInstanceOf(JwtException.class);
    jwks = new StubJwksServer();
  }

  private void assertRejected(String token) {
    assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
  }
}
