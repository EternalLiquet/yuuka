package com.yuuka.backend.mcp.security;

import com.yuuka.backend.auth.domain.UserAccount;
import com.yuuka.backend.auth.infrastructure.JpaUserAccountRepository;
import com.yuuka.backend.mcp.config.ConditionalOnMcpEnabled;
import com.yuuka.backend.mcp.config.McpProperties;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Maps a validated token's (issuer, subject) to an owner using only configured exact mappings. The
 * owner is never taken from tool arguments or from the subject claim itself.
 */
@Component
@ConditionalOnMcpEnabled
public class McpPrincipalResolver {
  private final McpProperties properties;
  private final JpaUserAccountRepository users;

  public McpPrincipalResolver(McpProperties properties, JpaUserAccountRepository users) {
    this.properties = properties;
    this.users = users;
  }

  public UUID resolve(Jwt jwt) {
    String issuer = jwt.getClaimAsString("iss");
    String subject = jwt.getSubject();
    if (issuer == null || subject == null) {
      throw new McpForbiddenException();
    }
    UUID ownerId =
        properties.ownerMappings().stream()
            .filter(m -> issuer.equals(m.issuer()) && subject.equals(m.subject()))
            .map(McpProperties.OwnerMapping::ownerId)
            .findFirst()
            .orElseThrow(McpForbiddenException::new);
    if (ownerId == null) {
      throw new McpForbiddenException();
    }
    UserAccount account = users.findById(ownerId).orElseThrow(McpForbiddenException::new);
    if (!account.isEnabled()) {
      throw new McpForbiddenException();
    }
    return ownerId;
  }
}
