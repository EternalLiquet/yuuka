package com.yuuka.backend.mcp.config;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** URL helpers shared by startup validation, the metadata handler and the 401 challenge. */
public final class McpUrls {
  /** Fixed RFC 9728 well-known prefix. */
  public static final String WELL_KNOWN_PREFIX = "/.well-known/oauth-protected-resource";

  /** The only path on which the MCP endpoint (and therefore the resource) is served. */
  public static final String RESOURCE_PATH = "/mcp";

  public static final String METADATA_PATH = WELL_KNOWN_PREFIX + RESOURCE_PATH;

  private McpUrls() {}

  /**
   * Metadata URL for a resource URI: scheme + authority + well-known prefix + resource path, for
   * example {@code https://h.example/mcp} gives {@code
   * https://h.example/.well-known/oauth-protected-resource/mcp}.
   */
  public static String metadataUrl(String resourceUri) {
    URI uri = URI.create(resourceUri);
    String path = uri.getRawPath() == null ? "" : uri.getRawPath();
    return uri.getScheme() + "://" + uri.getRawAuthority() + WELL_KNOWN_PREFIX + path;
  }

  /** Parses an absolute URI, returning null when it is malformed. */
  public static URI parse(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      URI uri = new URI(value.trim());
      return uri.getScheme() == null || uri.getHost() == null ? null : uri;
    } catch (URISyntaxException exception) {
      return null;
    }
  }

  /** True for {@code localhost} and literal loopback addresses; never performs a DNS lookup. */
  public static boolean isLoopbackHost(String host) {
    if (host == null) {
      return false;
    }
    String normalized = host.toLowerCase(Locale.ROOT);
    if (normalized.startsWith("[") && normalized.endsWith("]")) {
      normalized = normalized.substring(1, normalized.length() - 1);
    }
    if (normalized.equals("localhost")) {
      return true;
    }
    boolean literal = normalized.matches("[0-9.]+") || normalized.contains(":");
    if (!literal) {
      return false;
    }
    try {
      return InetAddress.getByName(normalized).isLoopbackAddress();
    } catch (java.net.UnknownHostException exception) {
      return false;
    }
  }

  /** https always; http only for loopback hosts (local tests). */
  public static boolean isHttpsOrLoopbackHttp(URI uri) {
    return "https".equalsIgnoreCase(uri.getScheme())
        || ("http".equalsIgnoreCase(uri.getScheme()) && isLoopbackHost(uri.getHost()));
  }
}
