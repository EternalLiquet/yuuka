package com.yuuka.backend.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import com.yuuka.backend.mcp.security.JwksMcpTokenDecoder;
import com.yuuka.backend.mcp.security.McpTokenDecoder;
import com.yuuka.backend.mcp.support.JwtMinter;
import com.yuuka.backend.mcp.support.StubJwksServer;
import com.yuuka.backend.mcp.support.SyntheticMcpTokens;
import com.yuuka.backend.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full context with the PRODUCTION decoder (no synthetic decoder imported) against a loopback stub
 * JWKS: properties alone register {@link JwksMcpTokenDecoder}.
 */
@AutoConfigureMockMvc
class McpProductionWiringTests extends AbstractIntegrationTest {
  private static final StubJwksServer JWKS = new StubJwksServer();
  private static final RSAKey KEY = JwtMinter.rsa("prod-k1");
  private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

  static {
    JWKS.publish(KEY.toPublicJWK());
  }

  @DynamicPropertySource
  static void mcp(DynamicPropertyRegistry registry) {
    registry.add("yuuka.mcp.enabled", () -> "true");
    registry.add("yuuka.mcp.resource-uri", () -> SyntheticMcpTokens.RESOURCE_URI);
    registry.add("yuuka.mcp.issuer", () -> SyntheticMcpTokens.ISSUER);
    registry.add("yuuka.mcp.jwk-set-uri", JWKS::jwkSetUri);
    registry.add("yuuka.mcp.owner-mappings[0].issuer", () -> SyntheticMcpTokens.ISSUER);
    registry.add("yuuka.mcp.owner-mappings[0].subject", () -> "subject-1");
    registry.add("yuuka.mcp.owner-mappings[0].owner-id", OWNER::toString);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private ApplicationContext context;
  @Autowired private JdbcTemplate jdbc;

  @AfterAll
  static void stop() {
    JWKS.close();
  }

  private String mint(RSAKey key, String audience) {
    return JwtMinter.sign(
        key,
        JWSAlgorithm.RS256,
        null,
        JwtMinter.claims(SyntheticMcpTokens.ISSUER, audience, Instant.now().plusSeconds(600), null)
            .build());
  }

  private int listTools(String token) throws Exception {
    return mockMvc
        .perform(
            post("/mcp")
                .header("Authorization", "Bearer " + token)
                .header("MCP-Protocol-Version", "2026-07-28")
                .header("Mcp-Method", "tools/list")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"}}}"))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Test
  void propertiesAloneRegisterTheProductionDecoderWithoutFetchingAtStartup() {
    Map<String, McpTokenDecoder> decoders = context.getBeansOfType(McpTokenDecoder.class);
    assertThat(decoders).hasSize(1);
    assertThat(decoders.values().iterator().next()).isInstanceOf(JwksMcpTokenDecoder.class);
    assertThat(context.getBeansOfType(org.springframework.security.oauth2.jwt.JwtDecoder.class))
        .hasSize(1); // only the ordinary REST decoder
  }

  @Test
  void validAsymmetricTokenWorksAndEverythingElseIsRejected() throws Exception {
    jdbc.update(
        """
        insert into user_accounts (id, email, password_hash, display_name, role, created_at, updated_at)
        values (?, 'mcp-prod-wiring@yuuka.test.invalid', 'not-a-real-hash', 'Synthetic', 'USER', now(), now())
        on conflict (id) do nothing
        """,
        OWNER);
    assertThat(listTools(mint(KEY, SyntheticMcpTokens.RESOURCE_URI))).isEqualTo(200);
    assertThat(JWKS.fetchCount()).isPositive();

    assertThat(listTools(mint(KEY, "https://other.test.invalid/mcp"))).isEqualTo(401);
    assertThat(listTools(mint(JwtMinter.rsa("prod-k1"), SyntheticMcpTokens.RESOURCE_URI)))
        .isEqualTo(401);
    var claims =
        JwtMinter.claims(
                SyntheticMcpTokens.ISSUER,
                SyntheticMcpTokens.RESOURCE_URI,
                Instant.now().plusSeconds(600),
                null)
            .build();
    assertThat(listTools(JwtMinter.none(claims))).isEqualTo(401);
    assertThat(
            listTools(
                JwtMinter.hs256(
                    "change-me-to-a-32-byte-minimum-secret".getBytes(), "prod-k1", claims)))
        .isEqualTo(401);
    mockMvc
        .perform(
            post("/api/v1/paychecks")
                .header("Authorization", "Bearer " + mint(KEY, SyntheticMcpTokens.RESOURCE_URI))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
  }
}
