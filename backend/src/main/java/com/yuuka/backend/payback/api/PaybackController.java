package com.yuuka.backend.payback.api;

import com.yuuka.backend.common.api.PageResponse;
import com.yuuka.backend.common.security.AuthenticatedOwner;
import com.yuuka.backend.payback.api.dto.CreatePaybackRequest;
import com.yuuka.backend.payback.api.dto.PaybackListResponse;
import com.yuuka.backend.payback.api.dto.PaybackRepaymentResponse;
import com.yuuka.backend.payback.api.dto.PaybackResponse;
import com.yuuka.backend.payback.api.dto.UpdatePaybackRequest;
import com.yuuka.backend.payback.application.PaybackService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/paybacks")
public class PaybackController {
  private final PaybackService paybackService;

  public PaybackController(PaybackService paybackService) {
    this.paybackService = paybackService;
  }

  @GetMapping
  public PaybackListResponse list(@AuthenticationPrincipal Jwt jwt) {
    return paybackService.list(AuthenticatedOwner.id(jwt));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public PaybackResponse create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreatePaybackRequest request) {
    return paybackService.create(AuthenticatedOwner.id(jwt), request);
  }

  @GetMapping("/{paybackId}")
  public PaybackResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID paybackId) {
    return paybackService.get(AuthenticatedOwner.id(jwt), paybackId);
  }

  @PatchMapping("/{paybackId}")
  public PaybackResponse update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID paybackId,
      @Valid @RequestBody UpdatePaybackRequest request) {
    return paybackService.update(AuthenticatedOwner.id(jwt), paybackId, request);
  }

  @DeleteMapping("/{paybackId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID paybackId, @RequestParam long version) {
    paybackService.delete(AuthenticatedOwner.id(jwt), paybackId, version);
  }

  @GetMapping("/{paybackId}/repayments")
  public PageResponse<PaybackRepaymentResponse> repayments(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID paybackId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return paybackService.repayments(AuthenticatedOwner.id(jwt), paybackId, page, size);
  }
}
