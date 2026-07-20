package com.yuuka.backend.dashboard.api;

import com.yuuka.backend.common.security.AuthenticatedOwner;
import com.yuuka.backend.dashboard.api.dto.DashboardSummaryResponse;
import com.yuuka.backend.dashboard.application.DashboardService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
  private final DashboardService dashboardService;

  public DashboardController(DashboardService dashboardService) {
    this.dashboardService = dashboardService;
  }

  @GetMapping("/summary")
  public DashboardSummaryResponse summary(@AuthenticationPrincipal Jwt jwt) {
    return dashboardService.summary(AuthenticatedOwner.id(jwt));
  }
}
