package com.yuuka.backend.auth.api;

import com.yuuka.backend.auth.api.dto.MeResponse;
import com.yuuka.backend.auth.api.dto.UpdateOwnerSettingsRequest;
import com.yuuka.backend.auth.application.OwnerSettingsService;
import com.yuuka.backend.auth.infrastructure.JpaUserAccountRepository;
import com.yuuka.backend.common.api.ResourceNotFoundException;
import com.yuuka.backend.common.security.AuthenticatedOwner;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {
  private final JpaUserAccountRepository users;
  private final OwnerSettingsService settingsService;

  public MeController(JpaUserAccountRepository users, OwnerSettingsService settingsService) {
    this.users = users;
    this.settingsService = settingsService;
  }

  @GetMapping
  public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
    return users
        .findById(AuthenticatedOwner.id(jwt))
        .map(MeResponse::from)
        .orElseThrow(ResourceNotFoundException::new);
  }

  @PatchMapping("/settings")
  public MeResponse updateSettings(
      @AuthenticationPrincipal Jwt jwt,
      @jakarta.validation.Valid @RequestBody UpdateOwnerSettingsRequest request) {
    return settingsService.update(AuthenticatedOwner.id(jwt), request);
  }
}
