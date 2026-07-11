package com.yuuka.backend.common.security;

import com.yuuka.backend.common.config.HttpProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestSizeFilter extends OncePerRequestFilter {
  private final HttpProperties properties;

  public RequestSizeFilter(HttpProperties properties) {
    this.properties = properties;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    long maximum = properties.maxRequestBodySizeBytes();
    long contentLength = request.getContentLengthLong();
    if (contentLength > maximum) {
      response.sendError(HttpStatus.PAYLOAD_TOO_LARGE.value());
      return;
    }
    if (!mayHaveBody(request) || contentLength == 0) {
      filterChain.doFilter(request, response);
      return;
    }

    byte[] body = readBounded(request, maximum);
    if (body == null) {
      response.sendError(HttpStatus.PAYLOAD_TOO_LARGE.value());
      return;
    }
    filterChain.doFilter(new CachedBodyRequest(request, body), response);
  }

  private boolean mayHaveBody(HttpServletRequest request) {
    String method = request.getMethod();
    return !HttpMethod.GET.matches(method)
        && !HttpMethod.HEAD.matches(method)
        && !HttpMethod.OPTIONS.matches(method);
  }

  private byte[] readBounded(HttpServletRequest request, long maximum) throws IOException {
    ByteArrayOutputStream body = new ByteArrayOutputStream((int) Math.min(maximum, 8192));
    byte[] buffer = new byte[8192];
    long total = 0;
    int read;
    ServletInputStream input = request.getInputStream();
    while ((read = input.read(buffer)) != -1) {
      total += read;
      if (total > maximum) {
        return null;
      }
      body.write(buffer, 0, read);
    }
    return body.toByteArray();
  }

  private static final class CachedBodyRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    private CachedBodyRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream input = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public boolean isFinished() {
          return input.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
          // Yuuka request bodies are consumed synchronously by Spring MVC.
        }

        @Override
        public int read() {
          return input.read();
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      Charset charset =
          getCharacterEncoding() == null
              ? StandardCharsets.UTF_8
              : Charset.forName(getCharacterEncoding());
      return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }
  }
}
