package com.yuuka.backend.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yuuka.backend.BackendApplication;
import com.yuuka.backend.mcp.support.AbstractMcpTest;
import com.yuuka.backend.mcp.support.SyntheticMcpAuthConfig;
import com.yuuka.backend.mcp.support.SyntheticMcpTokens;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * ADR 4.3 revocation (a): local denial is bound at startup. The same synthetic token is accepted by
 * the running context, then a fresh application context (the restart) with the mapping removed, or
 * with the feature disabled, denies it. There is no hot reload.
 */
class McpLocalDenialAfterRestartTests extends AbstractMcpTest {
  @Autowired private Environment environment;

  private ConfigurableApplicationContext restart(String... mcpProperties) {
    List<String> properties = new ArrayList<>();
    for (String key : new String[] {"url", "username", "password"}) {
      properties.add(
          "spring.datasource." + key + "=" + environment.getProperty("spring.datasource." + key));
    }
    properties.add("server.port=0");
    properties.add("yuuka.mcp.resource-uri=" + SyntheticMcpTokens.RESOURCE_URI);
    properties.add("yuuka.mcp.issuer=" + SyntheticMcpTokens.ISSUER);
    properties.add("yuuka.mcp.jwk-set-uri=" + SyntheticMcpTokens.ISSUER + "/jwks");
    properties.addAll(List.of(mcpProperties));
    return new SpringApplicationBuilder(BackendApplication.class, SyntheticMcpAuthConfig.class)
        .web(WebApplicationType.SERVLET)
        .run(properties.stream().map(p -> "--" + p).toArray(String[]::new));
  }

  private MvcResult listTools(ConfigurableApplicationContext context, String token)
      throws Exception {
    MockMvc restarted =
        MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
            .apply(springSecurity())
            .build();
    return restarted
        .perform(modernRequest(token, "tools/list", null, modernBody(1, "tools/list", null)))
        .andReturn();
  }

  @Test
  void sameTokenIsDeniedAfterRestartWithMappingRemovedOrFeatureDisabled() throws Exception {
    ensureOwner(1);
    ensureOwner(2);
    String token = mcpToken(1);
    mockMvc
        .perform(modernRequest(token, "tools/list", null, modernBody(1, "tools/list", null)))
        .andExpect(status().isOk());

    try (ConfigurableApplicationContext mappingRemoved =
        restart(
            "yuuka.mcp.enabled=true",
            "yuuka.mcp.owner-mappings[0].issuer=" + SyntheticMcpTokens.ISSUER,
            "yuuka.mcp.owner-mappings[0].subject=subject-2",
            "yuuka.mcp.owner-mappings[0].owner-id=" + ownerId(2))) {
      MvcResult denied = listTools(mappingRemoved, token);
      assertThat(denied.getResponse().getStatus()).isEqualTo(403);
      assertThat(denied.getResponse().getContentAsString()).doesNotContain("tools", "owner");
      // The remaining mapping still works there, so the denial is specific to the removed one.
      assertThat(listTools(mappingRemoved, mcpToken(2)).getResponse().getStatus()).isEqualTo(200);
    }

    try (ConfigurableApplicationContext disabled = restart("yuuka.mcp.enabled=false")) {
      MvcResult denied = listTools(disabled, token);
      assertThat(denied.getResponse().getStatus()).isIn(401, 403);
      assertThat(denied.getResponse().getContentAsString()).doesNotContain("tools", "result");
    }
  }
}
