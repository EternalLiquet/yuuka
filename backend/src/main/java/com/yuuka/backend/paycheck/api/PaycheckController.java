package com.yuuka.backend.paycheck.api;

import com.yuuka.backend.audit.api.dto.AuditEventResponse;
import com.yuuka.backend.audit.application.AuditService;
import com.yuuka.backend.common.api.PageResponse;
import com.yuuka.backend.common.security.AuthenticatedOwner;
import com.yuuka.backend.paycheck.api.dto.CreateEntryRequest;
import com.yuuka.backend.paycheck.api.dto.CreatePaycheckRequest;
import com.yuuka.backend.paycheck.api.dto.EntryResponse;
import com.yuuka.backend.paycheck.api.dto.PaycheckResponse;
import com.yuuka.backend.paycheck.api.dto.ReorderEntriesRequest;
import com.yuuka.backend.paycheck.api.dto.StatusChangeRequest;
import com.yuuka.backend.paycheck.api.dto.StatusEventResponse;
import com.yuuka.backend.paycheck.api.dto.UpdateEntryRequest;
import com.yuuka.backend.paycheck.api.dto.UpdatePaycheckRequest;
import com.yuuka.backend.paycheck.api.dto.VersionRequest;
import com.yuuka.backend.paycheck.application.PaycheckService;
import com.yuuka.backend.paycheck.domain.EntryStatus;
import com.yuuka.backend.paycheck.domain.EntryType;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
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
@RequestMapping("/api/v1")
public class PaycheckController {
  private final PaycheckService paycheckService;
  private final AuditService auditService;

  public PaycheckController(PaycheckService paycheckService, AuditService auditService) {
    this.paycheckService = paycheckService;
    this.auditService = auditService;
  }

  @GetMapping("/paychecks/active")
  public PageResponse<PaycheckResponse> active(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return paycheckService.active(AuthenticatedOwner.id(jwt), page, size);
  }

  @GetMapping("/paychecks/history")
  public PageResponse<PaycheckResponse> history(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(defaultValue = "false") boolean oldestFirst,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return paycheckService.history(
        AuthenticatedOwner.id(jwt), search, from, to, oldestFirst, page, size);
  }

  @PostMapping("/paychecks")
  @ResponseStatus(HttpStatus.CREATED)
  public PaycheckResponse create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreatePaycheckRequest request) {
    return paycheckService.create(AuthenticatedOwner.id(jwt), request);
  }

  @GetMapping("/paychecks/{paycheckId}")
  public PaycheckResponse get(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID paycheckId,
      @RequestParam(required = false) EntryStatus status,
      @RequestParam(required = false) EntryType type,
      @RequestParam(defaultValue = "custom") String sort,
      @RequestParam(defaultValue = "true") boolean ascending) {
    return paycheckService.get(
        AuthenticatedOwner.id(jwt), paycheckId, status, type, sort, ascending);
  }

  @PatchMapping("/paychecks/{paycheckId}")
  public PaycheckResponse update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID paycheckId,
      @Valid @RequestBody UpdatePaycheckRequest request) {
    return paycheckService.update(AuthenticatedOwner.id(jwt), paycheckId, request);
  }

  @PostMapping("/paychecks/{paycheckId}/close")
  public PaycheckResponse close(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID paycheckId,
      @Valid @RequestBody VersionRequest request) {
    return paycheckService.close(AuthenticatedOwner.id(jwt), paycheckId, request.version());
  }

  @PostMapping("/paychecks/{paycheckId}/reopen")
  public PaycheckResponse reopen(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID paycheckId,
      @Valid @RequestBody VersionRequest request) {
    return paycheckService.reopen(AuthenticatedOwner.id(jwt), paycheckId, request.version());
  }

  @DeleteMapping("/paychecks/{paycheckId}")
  public PaycheckResponse archive(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID paycheckId, @RequestParam long version) {
    return paycheckService.archive(AuthenticatedOwner.id(jwt), paycheckId, version);
  }

  @PostMapping("/paychecks/{paycheckId}/entries")
  @ResponseStatus(HttpStatus.CREATED)
  public EntryResponse addEntry(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID paycheckId,
      @Valid @RequestBody CreateEntryRequest request) {
    return paycheckService.addEntry(AuthenticatedOwner.id(jwt), paycheckId, request);
  }

  @PostMapping("/paychecks/{paycheckId}/entries/reorder")
  public PaycheckResponse reorder(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID paycheckId,
      @Valid @RequestBody ReorderEntriesRequest request) {
    return paycheckService.reorder(AuthenticatedOwner.id(jwt), paycheckId, request);
  }

  @PatchMapping("/entries/{entryId}")
  public EntryResponse updateEntry(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID entryId,
      @Valid @RequestBody UpdateEntryRequest request) {
    return paycheckService.updateEntry(AuthenticatedOwner.id(jwt), entryId, request);
  }

  @DeleteMapping("/entries/{entryId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteEntry(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID entryId, @RequestParam long version) {
    paycheckService.deleteEntry(AuthenticatedOwner.id(jwt), entryId, version);
  }

  @PostMapping("/entries/{entryId}/status")
  public EntryResponse changeStatus(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID entryId,
      @Valid @RequestBody StatusChangeRequest request) {
    return paycheckService.changeStatus(AuthenticatedOwner.id(jwt), entryId, request);
  }

  @GetMapping("/entries/{entryId}/status-history")
  public PageResponse<StatusEventResponse> statusHistory(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID entryId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return paycheckService.statusHistory(AuthenticatedOwner.id(jwt), entryId, page, size);
  }

  @GetMapping("/paychecks/{paycheckId}/audit")
  public PageResponse<AuditEventResponse> paycheckAudit(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID paycheckId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    UUID ownerId = AuthenticatedOwner.id(jwt);
    paycheckService.requirePaycheck(ownerId, paycheckId);
    return auditService.findForEntity(ownerId, "PAYCHECK", paycheckId, page, size);
  }

  @GetMapping("/entries/{entryId}/audit")
  public PageResponse<AuditEventResponse> entryAudit(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID entryId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    UUID ownerId = AuthenticatedOwner.id(jwt);
    paycheckService.requireEntry(ownerId, entryId);
    return auditService.findForEntity(ownerId, "PAYCHECK_ENTRY", entryId, page, size);
  }
}
