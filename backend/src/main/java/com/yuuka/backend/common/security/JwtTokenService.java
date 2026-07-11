package com.yuuka.backend.common.security;

import com.yuuka.backend.auth.domain.UserAccount;
import com.yuuka.backend.common.config.JwtProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {
  private final JwtEncoder jwtEncoder;
  private final JwtProperties properties;
  private final Clock clock;

  public JwtTokenService(JwtEncoder jwtEncoder, JwtProperties properties, Clock clock) {
    this.jwtEncoder = jwtEncoder;
    this.properties = properties;
    this.clock = clock;
  }

  public AccessToken createAccessToken(UserAccount userAccount) {
    Instant now = clock.instant();
    Instant expiresAt = now.plus(properties.accessTokenTtl());
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(properties.issuer())
            .issuedAt(now)
            .expiresAt(expiresAt)
            .id(UUID.randomUUID().toString())
            .subject(userAccount.getId().toString())
            .audience(List.of(properties.audience()))
            .claim("email", userAccount.getEmail())
            .claim("roles", List.of(userAccount.getRole().authority()))
            .build();

    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    return new AccessToken(token, expiresAt);
  }

  public record AccessToken(String value, Instant expiresAt) {}
}
