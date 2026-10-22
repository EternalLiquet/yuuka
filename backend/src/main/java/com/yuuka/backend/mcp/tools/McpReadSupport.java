package com.yuuka.backend.mcp.tools;

import com.yuuka.backend.auth.domain.UserAccount;
import com.yuuka.backend.auth.infrastructure.JpaUserAccountRepository;
import com.yuuka.backend.common.api.PageResponse;
import com.yuuka.backend.mcp.config.ConditionalOnMcpEnabled;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Builds the common read envelope from the owner profile and the injected clock. */
@Component
@ConditionalOnMcpEnabled
public class McpReadSupport {
  private final JpaUserAccountRepository users;
  private final Clock clock;

  public McpReadSupport(JpaUserAccountRepository users, Clock clock) {
    this.users = users;
    this.clock = clock;
  }

  /** Captures the read start time and owner-local context. */
  public ReadContext begin(UUID ownerId) {
    Instant started = clock.instant();
    UserAccount owner = users.findById(ownerId).orElseThrow(McpToolFailure::notFound);
    ZoneId zone = zone(owner.getTimezone());
    return new ReadContext(
        started, LocalDate.ofInstant(started, zone), zone.getId(), owner.getCurrencyCode());
  }

  public <T> McpReadEnvelope<T> success(
      ReadContext context,
      String tool,
      String scope,
      List<String> limitations,
      Map<String, Object> filters,
      McpReadEnvelope.Page page,
      boolean truncated,
      T data) {
    return success(
        context,
        tool,
        scope,
        limitations,
        filters,
        page,
        truncated,
        McpReadEnvelope.MULTI_READ,
        data);
  }

  /**
   * Same as the default overload but states the consistency the caller can actually guarantee.
   * {@code SNAPSHOT} is only truthful when every read behind {@code data} ran inside one
   * repeatable-read transaction.
   */
  public <T> McpReadEnvelope<T> success(
      ReadContext context,
      String tool,
      String scope,
      List<String> limitations,
      Map<String, Object> filters,
      McpReadEnvelope.Page page,
      boolean truncated,
      String consistency,
      T data) {
    return new McpReadEnvelope<>(
        McpReadEnvelope.SCHEMA_VERSION,
        tool,
        context.startedAt(),
        clock.instant(),
        context.asOfDate(),
        context.timeZone(),
        context.currency(),
        consistency,
        new McpReadEnvelope.Coverage(scope, List.copyOf(limitations)),
        filters,
        page,
        truncated,
        null,
        false,
        data);
  }

  /** Failure envelope: no data, populated failure, owner-local context. */
  public McpReadEnvelope<Object> failure(UUID ownerId, String tool, McpToolFailure failure) {
    ReadContext context = begin(ownerId);
    return new McpReadEnvelope<>(
        McpReadEnvelope.SCHEMA_VERSION,
        tool,
        context.startedAt(),
        clock.instant(),
        context.asOfDate(),
        context.timeZone(),
        context.currency(),
        McpReadEnvelope.MULTI_READ,
        new McpReadEnvelope.Coverage("none", List.of()),
        Map.of(),
        null,
        false,
        new McpReadEnvelope.Failure(failure.code(), failure.getMessage(), failure.retryable()),
        false,
        null);
  }

  public static McpReadEnvelope.Page page(PageResponse<?> response) {
    return new McpReadEnvelope.Page(
        response.page(),
        response.size(),
        McpValues.count(response.totalItems()),
        response.totalPages(),
        response.hasNext());
  }

  private static ZoneId zone(String timezone) {
    try {
      return ZoneId.of(timezone);
    } catch (DateTimeException exception) {
      return ZoneId.of("UTC");
    }
  }

  public record ReadContext(
      Instant startedAt, LocalDate asOfDate, String timeZone, String currency) {}
}
