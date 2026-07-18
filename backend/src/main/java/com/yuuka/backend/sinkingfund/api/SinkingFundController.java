package com.yuuka.backend.sinkingfund.api;

import com.yuuka.backend.common.api.PageResponse;
import com.yuuka.backend.common.security.AuthenticatedOwner;
import com.yuuka.backend.sinkingfund.api.dto.CreateSinkingFundRequest;
import com.yuuka.backend.sinkingfund.api.dto.CreateSinkingFundWithdrawalRequest;
import com.yuuka.backend.sinkingfund.api.dto.ReorderSinkingFundsRequest;
import com.yuuka.backend.sinkingfund.api.dto.ReverseSinkingFundWithdrawalRequest;
import com.yuuka.backend.sinkingfund.api.dto.SinkingFundListResponse;
import com.yuuka.backend.sinkingfund.api.dto.SinkingFundResponse;
import com.yuuka.backend.sinkingfund.api.dto.SinkingFundTransactionResponse;
import com.yuuka.backend.sinkingfund.api.dto.SinkingFundVersionRequest;
import com.yuuka.backend.sinkingfund.api.dto.UpdateSinkingFundRequest;
import com.yuuka.backend.sinkingfund.application.SinkingFundService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
@RequestMapping("/api/v1")
public class SinkingFundController {
  private final SinkingFundService service;

  public SinkingFundController(SinkingFundService service) {
    this.service = service;
  }

  @GetMapping("/sinking-funds")
  public SinkingFundListResponse list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "false") boolean includeArchived) {
    return service.list(AuthenticatedOwner.id(jwt), includeArchived);
  }

  @PostMapping("/sinking-funds")
  @ResponseStatus(HttpStatus.CREATED)
  public SinkingFundResponse create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateSinkingFundRequest request) {
    return service.create(AuthenticatedOwner.id(jwt), request);
  }

  @GetMapping("/sinking-funds/{fundId}")
  public SinkingFundResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID fundId) {
    return service.get(AuthenticatedOwner.id(jwt), fundId);
  }

  @PatchMapping("/sinking-funds/{fundId}")
  public SinkingFundResponse update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID fundId,
      @Valid @RequestBody UpdateSinkingFundRequest request) {
    return service.update(AuthenticatedOwner.id(jwt), fundId, request);
  }

  @PostMapping("/sinking-funds/{fundId}/archive")
  public SinkingFundResponse archive(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID fundId,
      @Valid @RequestBody SinkingFundVersionRequest request) {
    return service.archive(AuthenticatedOwner.id(jwt), fundId, request);
  }

  @PostMapping("/sinking-funds/{fundId}/restore")
  public SinkingFundResponse restore(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID fundId,
      @Valid @RequestBody SinkingFundVersionRequest request) {
    return service.restore(AuthenticatedOwner.id(jwt), fundId, request);
  }

  @PostMapping("/sinking-funds/reorder")
  public SinkingFundListResponse reorder(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ReorderSinkingFundsRequest request) {
    return service.reorder(AuthenticatedOwner.id(jwt), request);
  }

  @GetMapping("/sinking-funds/{fundId}/transactions")
  public PageResponse<SinkingFundTransactionResponse> transactions(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID fundId,
      @Parameter(
              schema =
                  @Schema(
                      implementation = Integer.class,
                      type = "integer",
                      format = "int32",
                      minimum = "0",
                      defaultValue = "0"))
          @RequestParam(defaultValue = "0")
          int page,
      @Parameter(
              schema =
                  @Schema(
                      implementation = Integer.class,
                      type = "integer",
                      format = "int32",
                      minimum = "1",
                      maximum = "100",
                      defaultValue = "50"))
          @RequestParam(defaultValue = "50")
          int size) {
    return service.transactions(AuthenticatedOwner.id(jwt), fundId, page, size);
  }

  @PostMapping("/sinking-funds/{fundId}/withdrawals")
  @ResponseStatus(HttpStatus.CREATED)
  public SinkingFundTransactionResponse withdraw(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID fundId,
      @Valid @RequestBody CreateSinkingFundWithdrawalRequest request) {
    return service.withdraw(AuthenticatedOwner.id(jwt), fundId, request);
  }

  @PostMapping("/sinking-fund-transactions/{transactionId}/reverse")
  public SinkingFundTransactionResponse reverseWithdrawal(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID transactionId,
      @Valid @RequestBody ReverseSinkingFundWithdrawalRequest request) {
    return service.reverseWithdrawal(AuthenticatedOwner.id(jwt), transactionId, request);
  }
}
