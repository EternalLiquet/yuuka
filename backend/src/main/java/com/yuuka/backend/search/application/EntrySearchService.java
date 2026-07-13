package com.yuuka.backend.search.application;

import com.yuuka.backend.common.api.BusinessRuleException;
import com.yuuka.backend.common.api.PageResponse;
import com.yuuka.backend.paycheck.domain.PaycheckVisibilityPolicy;
import com.yuuka.backend.search.api.dto.EntrySearchResultResponse;
import com.yuuka.backend.search.domain.PaycheckContext;
import com.yuuka.backend.search.domain.SearchResultKind;
import com.yuuka.backend.search.domain.SearchScope;
import com.yuuka.backend.search.infrastructure.EntrySearchProjection;
import com.yuuka.backend.search.infrastructure.JpaEntrySearchRepository;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EntrySearchService {
  private static final int MAX_PAGE_SIZE = 100;
  private static final char LIKE_ESCAPE = '!';

  private final JpaEntrySearchRepository searches;
  private final PaycheckVisibilityPolicy visibilityPolicy;

  public EntrySearchService(
      JpaEntrySearchRepository searches, PaycheckVisibilityPolicy visibilityPolicy) {
    this.searches = searches;
    this.visibilityPolicy = visibilityPolicy;
  }

  @Transactional(readOnly = true)
  public PageResponse<EntrySearchResultResponse> search(
      UUID ownerId, String query, Long amountMinor, SearchScope scope, int page, int size) {
    String normalizedQuery = normalizeQuery(query);
    if (normalizedQuery == null && amountMinor == null) {
      throw new BusinessRuleException(
          "SEARCH_CRITERIA_REQUIRED",
          "Enter a name or amount before searching.",
          Map.of("query", "A search term or amount is required."));
    }
    String likeQuery = normalizedQuery == null ? null : escapeLikeQuery(normalizedQuery);
    Page<EntrySearchResultResponse> results =
        searches
            .searchEntries(
                ownerId,
                normalizedQuery,
                likeQuery,
                amountMinor,
                scope == SearchScope.ACTIVE,
                scope == SearchScope.HISTORY,
                PageRequest.of(normalizePage(page), normalizeSize(size)))
            .map(this::toResponse);
    return PageResponse.from(results);
  }

  private EntrySearchResultResponse toResponse(EntrySearchProjection result) {
    PaycheckContext paycheckContext =
        visibilityPolicy.belongsInActive(
                result.getPaycheckState(), result.getRequiresAttention(), result.getReopenedAt())
            ? PaycheckContext.ACTIVE
            : PaycheckContext.HISTORY;
    return new EntrySearchResultResponse(
        SearchResultKind.PAYCHECK_ENTRY,
        result.getEntryId(),
        result.getPaycheckId(),
        result.getEntryName(),
        result.getAmountMinor(),
        result.getEntryType(),
        result.getPaymentMethod(),
        result.getStatus(),
        result.getPaycheckName(),
        result.getPaycheckIncomeDate(),
        paycheckContext);
  }

  private String normalizeQuery(String query) {
    if (query == null) {
      return null;
    }
    String trimmed = query.trim().toLowerCase(Locale.ROOT);
    return trimmed.isEmpty() ? null : trimmed;
  }

  private String escapeLikeQuery(String query) {
    StringBuilder escaped = new StringBuilder(query.length());
    for (int index = 0; index < query.length(); index++) {
      char value = query.charAt(index);
      if (value == LIKE_ESCAPE || value == '%' || value == '_') {
        escaped.append(LIKE_ESCAPE);
      }
      escaped.append(value);
    }
    return escaped.toString();
  }

  private int normalizePage(int page) {
    return Math.max(page, 0);
  }

  private int normalizeSize(int size) {
    return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
  }
}
