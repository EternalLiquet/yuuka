package com.yuuka.backend.bucket.api;

import com.yuuka.backend.auth.application.OwnerLocalDateService;
import com.yuuka.backend.bucket.api.dto.RollingSpendingBucketPerformanceResponse;
import com.yuuka.backend.bucket.application.SpendingBucketPerformanceService;
import com.yuuka.backend.common.security.AuthenticatedOwner;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
    return rollingFor(jwt, SpendingBucketPerformanceService.NINETY_DAY_WINDOW, asOfDate);
  }

  @GetMapping("/spending-buckets/performance/rolling")
  public RollingSpendingBucketPerformanceResponse rolling(
      @AuthenticationPrincipal Jwt jwt,
      @Parameter(
              schema =
                  @Schema(
                      allowableValues = {"30", "90"},
                      defaultValue = "30"))
          @RequestParam(defaultValue = "30")
          int days,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate asOfDate) {
    return rollingFor(jwt, supportedDays(days), asOfDate);
  }

  private RollingSpendingBucketPerformanceResponse rollingFor(
      Jwt jwt, int days, LocalDate asOfDate) {
    UUID ownerId = AuthenticatedOwner.id(jwt);
    LocalDate effectiveDate =
        asOfDate == null ? ownerLocalDateService.currentDate(ownerId) : asOfDate;
    return service.rollingDays(ownerId, effectiveDate, days);
  }

  private int supportedDays(int days) {
    if (SpendingBucketPerformanceService.isSupportedRollingWindow(days)) {
      return days;
    }
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days must be 30 or 90.");
  }
}
