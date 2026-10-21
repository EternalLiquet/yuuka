package com.yuuka.backend.mcp.support;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Loopback-only JDK stub of an authorization server's JWKS endpoint (public keys only). */
public final class StubJwksServer implements AutoCloseable {
  private final HttpServer server;
  private final AtomicInteger fetches = new AtomicInteger();
  private volatile String body = "{\"keys\":[]}";

  public StubJwksServer() {
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
    server.createContext(
        "/jwks",
        exchange -> {
          fetches.incrementAndGet();
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/jwk-set+json");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
  }

  /** Publishes exactly these keys (public parts only). */
  public void publish(JWK... keys) {
    JWKSet set = new JWKSet(List.of(keys));
    this.body = set.toJSONObject(true).toString();
  }

  public int fetchCount() {
    return fetches.get();
  }

  public String jwkSetUri() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
