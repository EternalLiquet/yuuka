package com.yuuka.backend.recurring.api;

import com.yuuka.backend.common.security.AuthenticatedOwner;
import com.yuuka.backend.paycheck.api.dto.PaycheckResponse;
import com.yuuka.backend.paycheck.api.dto.VersionRequest;
import com.yuuka.backend.recurring.api.dto.CreateRecurringBillRequest;
import com.yuuka.backend.recurring.api.dto.RecurringBillImportRequest;
import com.yuuka.backend.recurring.api.dto.RecurringBillListResponse;
import com.yuuka.backend.recurring.api.dto.RecurringBillResponse;
import com.yuuka.backend.recurring.api.dto.RecurringBillTimelineResponse;
import com.yuuka.backend.recurring.api.dto.UpdateRecurringBillRequest;
import com.yuuka.backend.recurring.application.RecurringBillService;
import com.yuuka.backend.recurring.domain.RecurringBillStatusFilter;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class RecurringBillController {
  private final RecurringBillService recurringBills;

  public RecurringBillController(RecurringBillService recurringBills) {
    this.recurringBills = recurringBills;
  }

  @GetMapping("/recurring-bills")
  public RecurringBillListResponse list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "ACTIVE") RecurringBillStatusFilter status,
      @RequestParam(required = false) String search) {
    return recurringBills.list(AuthenticatedOwner.id(jwt), status, search);
  }

  @PostMapping("/recurring-bills")
  @ResponseStatus(HttpStatus.CREATED)
  public RecurringBillResponse create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRecurringBillRequest request) {
    return recurringBills.create(AuthenticatedOwner.id(jwt), request);
  }

  @GetMapping("/recurring-bills/timeline")
  public RecurringBillTimelineResponse timeline(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate through) {
    return recurringBills.timeline(AuthenticatedOwner.id(jwt), from, through);
  }

  @GetMapping("/recurring-bills/{definitionId}")
  public RecurringBillResponse get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID definitionId) {
    return recurringBills.get(AuthenticatedOwner.id(jwt), definitionId);
  }

  @PutMapping("/recurring-bills/{definitionId}")
  public RecurringBillResponse update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID definitionId,
      @Valid @RequestBody UpdateRecurringBillRequest request) {
    return recurringBills.update(AuthenticatedOwner.id(jwt), definitionId, request);
  }

  @PostMapping("/recurring-bills/{definitionId}/activate")
  public RecurringBillResponse activate(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID definitionId,
      @Valid @RequestBody VersionRequest request) {
    return recurringBills.activate(AuthenticatedOwner.id(jwt), definitionId, request.version());
  }

  @PostMapping("/recurring-bills/{definitionId}/deactivate")
  public RecurringBillResponse deactivate(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID definitionId,
      @Valid @RequestBody VersionRequest request) {
    return recurringBills.deactivate(AuthenticatedOwner.id(jwt), definitionId, request.version());
  }

  @DeleteMapping("/recurring-bills/{definitionId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID definitionId,
      @RequestParam long version) {
    recurringBills.delete(AuthenticatedOwner.id(jwt), definitionId, version);
  }

  @PostMapping("/paychecks/{paycheckId}/recurring-bill-imports")
  public PaycheckResponse importIntoPaycheck(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID paycheckId,
      @Valid @RequestBody RecurringBillImportRequest request) {
    return recurringBills.importIntoPaycheck(AuthenticatedOwner.id(jwt), paycheckId, request);
  }
}
