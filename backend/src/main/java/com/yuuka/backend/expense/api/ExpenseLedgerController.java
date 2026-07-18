package com.yuuka.backend.expense.api;

import com.yuuka.backend.common.api.PageResponse;
import com.yuuka.backend.common.security.AuthenticatedOwner;
import com.yuuka.backend.expense.api.dto.CreateExpenseLedgerItemRequest;
import com.yuuka.backend.expense.api.dto.CreateExpenseLedgerRequest;
import com.yuuka.backend.expense.api.dto.ExpenseLedgerItemResponse;
import com.yuuka.backend.expense.api.dto.ExpenseLedgerResponse;
import com.yuuka.backend.expense.api.dto.ExpenseLedgerSettlementResultResponse;
import com.yuuka.backend.expense.api.dto.SettleExpenseLedgerAsBillRequest;
import com.yuuka.backend.expense.api.dto.SettleExpenseLedgerAsPaybackRequest;
import com.yuuka.backend.expense.api.dto.UpdateExpenseLedgerItemRequest;
import com.yuuka.backend.expense.api.dto.UpdateExpenseLedgerRequest;
import com.yuuka.backend.expense.application.ExpenseLedgerService;
import com.yuuka.backend.expense.domain.ExpenseLedgerState;
import com.yuuka.backend.paycheck.api.dto.VersionRequest;
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
@RequestMapping("/api/v1/expense-ledgers")
public class ExpenseLedgerController {
  private final ExpenseLedgerService expenseLedgerService;

  public ExpenseLedgerController(ExpenseLedgerService expenseLedgerService) {
    this.expenseLedgerService = expenseLedgerService;
  }

  @GetMapping
  public PageResponse<ExpenseLedgerResponse> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) ExpenseLedgerState state,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return expenseLedgerService.list(AuthenticatedOwner.id(jwt), state, page, size);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ExpenseLedgerResponse create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateExpenseLedgerRequest request) {
    return expenseLedgerService.create(AuthenticatedOwner.id(jwt), request);
  }

  @GetMapping("/{ledgerId}")
  public ExpenseLedgerResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID ledgerId) {
    return expenseLedgerService.get(AuthenticatedOwner.id(jwt), ledgerId);
  }

  @PatchMapping("/{ledgerId}")
  public ExpenseLedgerResponse update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID ledgerId,
      @Valid @RequestBody UpdateExpenseLedgerRequest request) {
    return expenseLedgerService.update(AuthenticatedOwner.id(jwt), ledgerId, request);
  }

  @DeleteMapping("/{ledgerId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID ledgerId, @RequestParam long version) {
    expenseLedgerService.delete(AuthenticatedOwner.id(jwt), ledgerId, version);
  }

  @PostMapping("/{ledgerId}/items")
  @ResponseStatus(HttpStatus.CREATED)
  public ExpenseLedgerItemResponse createItem(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID ledgerId,
      @Valid @RequestBody CreateExpenseLedgerItemRequest request) {
    return expenseLedgerService.createItem(AuthenticatedOwner.id(jwt), ledgerId, request);
  }

  @PatchMapping("/items/{itemId}")
  public ExpenseLedgerItemResponse updateItem(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID itemId,
      @Valid @RequestBody UpdateExpenseLedgerItemRequest request) {
    return expenseLedgerService.updateItem(AuthenticatedOwner.id(jwt), itemId, request);
  }

  @DeleteMapping("/items/{itemId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteItem(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID itemId, @RequestParam long version) {
    expenseLedgerService.deleteItem(AuthenticatedOwner.id(jwt), itemId, version);
  }

  @PostMapping("/{ledgerId}/finalize")
  public ExpenseLedgerResponse finalizeLedger(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID ledgerId,
      @Valid @RequestBody VersionRequest request) {
    return expenseLedgerService.finalizeLedger(
        AuthenticatedOwner.id(jwt), ledgerId, request.version());
  }

  @PostMapping("/{ledgerId}/reopen")
  public ExpenseLedgerResponse reopen(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID ledgerId,
      @Valid @RequestBody VersionRequest request) {
    return expenseLedgerService.reopen(AuthenticatedOwner.id(jwt), ledgerId, request.version());
  }

  @PostMapping("/{ledgerId}/settle/bill")
  public ExpenseLedgerSettlementResultResponse settleAsBill(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID ledgerId,
      @Valid @RequestBody SettleExpenseLedgerAsBillRequest request) {
    return expenseLedgerService.settleAsBill(AuthenticatedOwner.id(jwt), ledgerId, request);
  }

  @PostMapping("/{ledgerId}/settle/payback")
  public ExpenseLedgerSettlementResultResponse settleAsPayback(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID ledgerId,
      @Valid @RequestBody SettleExpenseLedgerAsPaybackRequest request) {
    return expenseLedgerService.settleAsPayback(AuthenticatedOwner.id(jwt), ledgerId, request);
  }
}
