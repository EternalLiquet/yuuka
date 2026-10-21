package com.yuuka.backend.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** HTTP status, optional JSON body, and extra headers for a response. */
public record McpHttpResult(int status, JsonNode body, Map<String, String> headers) {
  public McpHttpResult {
    headers = headers == null ? Map.of() : Map.copyOf(headers);
  }
}
