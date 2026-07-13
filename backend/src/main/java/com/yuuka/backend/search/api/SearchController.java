package com.yuuka.backend.search.api;

import com.yuuka.backend.common.api.PageResponse;
import com.yuuka.backend.common.security.AuthenticatedOwner;
import com.yuuka.backend.search.api.dto.EntrySearchResultResponse;
import com.yuuka.backend.search.application.EntrySearchService;
import com.yuuka.backend.search.domain.SearchScope;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/search")
public class SearchController {
  private final EntrySearchService entrySearchService;

  public SearchController(EntrySearchService entrySearchService) {
    this.entrySearchService = entrySearchService;
  }

  @GetMapping("/entries")
  public PageResponse<EntrySearchResultResponse> entries(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) String query,
      @RequestParam(required = false) Long amountMinor,
      @RequestParam(defaultValue = "ALL") SearchScope scope,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size) {
    UUID ownerId = AuthenticatedOwner.id(jwt);
    return entrySearchService.search(ownerId, query, amountMinor, scope, page, size);
  }
}
