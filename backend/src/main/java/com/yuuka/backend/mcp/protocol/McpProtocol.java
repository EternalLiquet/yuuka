package com.yuuka.backend.mcp.protocol;

import java.util.List;

/** Protocol constants shared by the MCP endpoint. */
public final class McpProtocol {
  /** Newest first. The first entry is the stateless per-request-metadata revision. */
  public static final List<String> SUPPORTED_PROTOCOL_VERSIONS =
      List.of("2026-07-28", "2025-11-25", "2025-06-18");

  public static final String STATELESS_VERSION = "2026-07-28";
  public static final String LEGACY_LATEST_VERSION = "2025-11-25";
  public static final String VERSION_META_KEY = "io.modelcontextprotocol/protocolVersion";
  public static final String SERVER_INFO_META_KEY = "io.modelcontextprotocol/serverInfo";
  public static final String SERVER_NAME = "yuuka";

  public static final int PARSE_ERROR = -32700;
  public static final int INVALID_REQUEST = -32600;
  public static final int METHOD_NOT_FOUND = -32601;
  public static final int INVALID_PARAMS = -32602;
  public static final int INTERNAL_ERROR = -32603;

  /** Implementation-defined: caller is authenticated but not permitted. */
  public static final int FORBIDDEN = -32000;

  public static final int HEADER_MISMATCH = -32020;
  public static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;

  private McpProtocol() {}
}
