package com.yuuka.backend.search.application;

import com.yuuka.backend.common.api.BusinessRuleException;
import com.yuuka.backend.common.api.PageResponse;
import com.yuuka.backend.paycheck.domain.PaycheckState;
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

  private final JpaEntrySearchRepository searches;

  public EntrySearchService(JpaEntrySearchRepository searches) {
    this.searches = searches;
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
    Page<EntrySearchResultResponse> results =
        searches
            .searchEntries(
                ownerId,
                normalizedQuery,
                amountMinor,
                scope == SearchScope.ACTIVE,
                scope == SearchScope.HISTORY,
                PageRequest.of(normalizePage(page), normalizeSize(size)))
            .map(this::toResponse);
    return PageResponse.from(results);
  }

  private EntrySearchResultResponse toResponse(EntrySearchProjection result) {
    return new EntrySearchResultResponse(
        SearchResultKind.PAYCHECK_ENTRY,
        result.getEntryId(),
        result.getPaycheckId(),
        result.getEntryName(),
        result.getAmountMinor(),
        result.getEntryType(),
        result.getStatus(),
        result.getPaycheckName(),
        result.getPaycheckIncomeDate(),
        result.getPaycheckState() == PaycheckState.ACTIVE
            ? PaycheckContext.ACTIVE
            : PaycheckContext.HISTORY);
  }

  private String normalizeQuery(String query) {
    if (query == null) {
      return null;
    }
    String trimmed = query.trim().toLowerCase(Locale.ROOT);
    return trimmed.isEmpty() ? null : trimmed;
  }

  private int normalizePage(int page) {
    return Math.max(page, 0);
  }

  private int normalizeSize(int size) {
    return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
  }
}
