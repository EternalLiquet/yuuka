package com.yuuka.backend.mcp.tools;

import com.yuuka.backend.mcp.config.ConditionalOnMcpEnabled;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMcpEnabled
public class McpToolRegistry {
  private final List<McpTool> tools;

  public McpToolRegistry(List<McpTool> tools) {
    this.tools = tools.stream().sorted(Comparator.comparing(McpTool::name)).toList();
  }

  public List<McpTool> tools() {
    return tools;
  }

  public Optional<McpTool> find(String name) {
    return tools.stream().filter(tool -> tool.name().equals(name)).findFirst();
  }
}
