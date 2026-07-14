package com.yuuka.backend.bucket.api;

import com.yuuka.backend.auth.application.OwnerLocalDateService;
import com.yuuka.backend.bucket.api.dto.RollingSpendingBucketPerformanceResponse;
import com.yuuka.backend.bucket.application.SpendingBucketPerformanceService;
import com.yuuka.backend.common.security.AuthenticatedOwner;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SpendingBucketPerformanceController {
  private final SpendingBucketPerformanceService service;
  private final OwnerLocalDateService ownerLocalDateService;

  public SpendingBucketPerformanceController(
      SpendingBucketPerformanceService service, OwnerLocalDateService ownerLocalDateService) {
    this.service = service;
    this.ownerLocalDateService = ownerLocalDateService;
  }

  @GetMapping("/spending-buckets/performance/rolling-90-days")
  public RollingSpendingBucketPerformanceResponse rolling90Days(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate asOfDate) {
    UUID ownerId = AuthenticatedOwner.id(jwt);
    LocalDate effectiveDate =
        asOfDate == null ? ownerLocalDateService.currentDate(ownerId) : asOfDate;
    return service.rolling90Days(ownerId, effectiveDate);
  }
}
