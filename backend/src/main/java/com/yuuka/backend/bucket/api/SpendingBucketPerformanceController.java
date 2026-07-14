package com.yuuka.backend.bucket.api;

import com.yuuka.backend.bucket.api.dto.RollingSpendingBucketPerformanceResponse;
import com.yuuka.backend.bucket.application.SpendingBucketPerformanceService;
import com.yuuka.backend.common.security.AuthenticatedOwner;
import java.time.LocalDate;
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

  public SpendingBucketPerformanceController(SpendingBucketPerformanceService service) {
    this.service = service;
  }

  @GetMapping("/spending-buckets/performance/rolling-90-days")
  public RollingSpendingBucketPerformanceResponse rolling90Days(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate) {
    return service.rolling90Days(AuthenticatedOwner.id(jwt), asOfDate);
  }
}
