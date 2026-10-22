package com.yuuka.backend.mcp.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

/**
 * Production {@link McpTokenDecoder}: validates asymmetric (RS256, ES256, PS256) JWT access tokens
 * against the external authorization server's JWKS. HS256 (including the Yuuka owner secret) and
 * {@code alg=none} are rejected by the algorithm allow-list.
 *
 * <p>The JWK set is fetched lazily on the first token, so startup has no provider dependency. It
 * uses Spring Security's default JWK source: Nimbus' in-memory cache with a 5-minute time to live,
 * and an immediate re-fetch when a token names an unknown {@code kid}. The TTL is intentionally not
 * configurable and no custom cache is implemented. A key removed from the provider's JWKS can
 * therefore still verify tokens for up to the TTL; this is key management, not revocation.
 */
public final class JwksMcpTokenDecoder implements McpTokenDecoder {
  /** Effective JWK set cache lifetime (Nimbus default under Spring Security 6.5). */
  public static final Duration JWK_CACHE_TTL = Duration.ofMinutes(5);

  private static final int CONNECT_TIMEOUT_MILLIS = 3_000;
  private static final int READ_TIMEOUT_MILLIS = 5_000;

  private final NimbusJwtDecoder delegate;

  public JwksMcpTokenDecoder(
      String issuer, String jwkSetUri, String resourceUri, Duration clockSkew, Clock clock) {
    Objects.requireNonNull(issuer, "issuer");
    Objects.requireNonNull(jwkSetUri, "jwkSetUri");
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
    requestFactory.setReadTimeout(READ_TIMEOUT_MILLIS);
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
            .restOperations(new RestTemplate(requestFactory))
            .jwsAlgorithms(
                algorithms -> {
                  algorithms.clear();
                  algorithms.add(SignatureAlgorithm.RS256);
                  algorithms.add(SignatureAlgorithm.ES256);
                  algorithms.add(SignatureAlgorithm.PS256);
                })
            // RFC 9068 access tokens use typ "at+jwt"; plain "JWT" and an absent typ stay accepted.
            .jwtProcessorCustomizer(
                processor ->
                    processor.setJWSTypeVerifier(
                        new DefaultJOSEObjectTypeVerifier<>(
                            JOSEObjectType.JWT, new JOSEObjectType("at+jwt"), null)))
            .build();
    JwtTimestampValidator timestamps = new JwtTimestampValidator(clockSkew);
    timestamps.setClock(clock);
    OAuth2TokenValidator<Jwt> expiryRequired = new JwtClaimValidator<>("exp", Objects::nonNull);
    OAuth2TokenValidator<Jwt> audience =
        new JwtClaimValidator<List<String>>(
            "aud", aud -> resourceUri != null && aud != null && aud.contains(resourceUri));
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            expiryRequired, timestamps, new JwtIssuerValidator(issuer), audience));
    this.delegate = decoder;
  }

  @Override
  public Jwt decode(String token) throws JwtException {
    return delegate.decode(token);
  }
}
