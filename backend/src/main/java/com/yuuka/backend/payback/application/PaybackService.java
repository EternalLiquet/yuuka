package com.yuuka.backend.payback.application;

import com.yuuka.backend.audit.application.AuditService;
import com.yuuka.backend.common.api.BusinessRuleException;
import com.yuuka.backend.common.api.ConflictException;
import com.yuuka.backend.common.api.PageResponse;
import com.yuuka.backend.common.api.ResourceNotFoundException;
import com.yuuka.backend.payback.api.dto.CreatePaybackRequest;
import com.yuuka.backend.payback.api.dto.PaybackListResponse;
import com.yuuka.backend.payback.api.dto.PaybackRepaymentResponse;
import com.yuuka.backend.payback.api.dto.PaybackResponse;
import com.yuuka.backend.payback.api.dto.PaybackSummaryResponse;
import com.yuuka.backend.payback.api.dto.ReorderPaybacksRequest;
import com.yuuka.backend.payback.api.dto.UpdatePaybackRequest;
import com.yuuka.backend.payback.domain.Payback;
import com.yuuka.backend.payback.domain.PaybackRepayment;
import com.yuuka.backend.payback.domain.PaybackState;
import com.yuuka.backend.payback.infrastructure.JpaPaybackRepaymentRepository;
import com.yuuka.backend.payback.infrastructure.JpaPaybackRepository;
import com.yuuka.backend.paycheck.domain.EntryStatus;
import com.yuuka.backend.paycheck.domain.Paycheck;
import com.yuuka.backend.paycheck.domain.PaycheckEntry;
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckEntryRepository;
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaybackService {
  private final JpaPaybackRepository paybacks;
  private final JpaPaybackRepaymentRepository repayments;
  private final JpaPaycheckEntryRepository entries;
  private final JpaPaycheckRepository paychecks;
  private final AuditService auditService;
  private final Clock clock;

  public PaybackService(
      JpaPaybackRepository paybacks,
      JpaPaybackRepaymentRepository repayments,
      JpaPaycheckEntryRepository entries,
      JpaPaycheckRepository paychecks,
      AuditService auditService,
      Clock clock) {
    this.paybacks = paybacks;
    this.repayments = repayments;
    this.entries = entries;
    this.paychecks = paychecks;
    this.auditService = auditService;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PaybackListResponse list(UUID ownerId) {
    List<PaybackResponse> items =
        paybacks.findAllByOwnerIdAndDeletedAtIsNull(ownerId).stream()
            .map(this::toResponse)
            .sorted(
                Comparator.comparing(
                        (PaybackResponse response) -> response.state() != PaybackState.ACTIVE)
                    .thenComparingInt(PaybackResponse::position)
                    .thenComparing(PaybackResponse::createdAt)
                    .thenComparing(PaybackResponse::id))
            .toList();
    long totalRemaining =
        items.stream()
            .filter(item -> item.state() == PaybackState.ACTIVE)
            .mapToLong(PaybackResponse::remainingMinor)
            .sum();
    long totalOriginal = items.stream().mapToLong(PaybackResponse::originalAmountMinor).sum();
    long totalRepaid = items.stream().mapToLong(PaybackResponse::repaidMinor).sum();
    int activeCount =
        (int) items.stream().filter(item -> item.state() == PaybackState.ACTIVE).count();
    return new PaybackListResponse(
        new PaybackSummaryResponse(totalRemaining, totalOriginal, totalRepaid, activeCount), items);
  }

  @Transactional(readOnly = true)
  public PaybackResponse get(UUID ownerId, UUID paybackId) {
    return toResponse(requirePayback(ownerId, paybackId));
  }

  @Transactional
  public PaybackResponse create(UUID ownerId, CreatePaybackRequest request) {
    validateBaseline(request.originalAmountMinor(), request.openingRemainingAmountMinor());
    Payback payback =
        paybacks.saveAndFlush(
            new Payback(
                ownerId,
                request.name().trim(),
                request.originalAmountMinor(),
                request.openingRemainingAmountMinor(),
                request.borrowedDate(),
                normalizeOptional(request.source()),
                normalizeOptional(request.notes()),
                paybacks.findMaxLivePosition(ownerId) + 1));
    PaybackResponse response = toResponse(payback);
    auditService.append(ownerId, "PAYBACK", payback.getId(), "CREATED", null, null, response, null);
    return response;
  }

  @Transactional
  public PaybackResponse update(UUID ownerId, UUID paybackId, UpdatePaybackRequest request) {
    Payback payback = requirePaybackForUpdate(ownerId, paybackId);
    assertVersion(payback.getVersion(), request.version());
    long repaidMinor = activeRepaidMinor(payback);
    validateBaseline(request.originalAmountMinor(), request.openingRemainingAmountMinor());
    if (request.openingRemainingAmountMinor() < repaidMinor) {
      throw new BusinessRuleException(
          "PAYBACK_BASELINE_BELOW_REPAYMENTS",
          "Balance when tracking began cannot be lower than recorded repayments.",
          Map.of(
              "amountMinor",
              repaidMinor - request.openingRemainingAmountMinor(),
              "currencyCode",
              "USD"));
    }
    PaybackResponse before = toResponse(payback);
    payback.update(
        request.name().trim(),
        request.originalAmountMinor(),
        request.openingRemainingAmountMinor(),
        request.borrowedDate(),
        normalizeOptional(request.source()),
        normalizeOptional(request.notes()),
        repaidMinor);
    paybacks.flush();
    PaybackResponse after = toResponse(payback);
    auditService.append(ownerId, "PAYBACK", paybackId, "UPDATED", null, before, after, null);
    return after;
  }

  @Transactional
  public void delete(UUID ownerId, UUID paybackId, long version) {
    Payback payback = requirePaybackForUpdate(ownerId, paybackId);
    assertVersion(payback.getVersion(), version);
    PaybackResponse before = toResponse(payback);
    Instant now = clock.instant();

    List<PaybackRepayment> activeRepayments =
        repayments.findActiveByPaybackIdAndOwnerIdForUpdate(paybackId, ownerId);
    activeRepayments.forEach(repayment -> repayment.reverse(now));
    repayments.flush();
    syncState(payback, now);

    List<PaycheckEntry> assignedEntries =
        entries.findLiveAssignedToPaybackForUpdate(paybackId, ownerId);
    Map<UUID, UUID> unassignedEntries =
        assignedEntries.stream()
            .collect(Collectors.toMap(PaycheckEntry::getId, PaycheckEntry::getPaycheckId));
    assignedEntries.forEach(entry -> entry.assignPayback(null));
    entries.flush();

    unassignedEntries.values().stream()
        .distinct()
        .sorted()
        .map(paycheckId -> paychecks.findByIdAndOwnerIdForUpdate(paycheckId, ownerId))
        .forEach(
            optionalPaycheck ->
                optionalPaycheck.orElseThrow(ResourceNotFoundException::new).touch(now));
    paychecks.flush();

    payback.delete(now);
    paybacks.flush();

    auditService.append(
        ownerId,
        "PAYBACK",
        paybackId,
        "DELETED",
        null,
        before,
        null,
        Map.of(
            "unassignedEntryIds",
            unassignedEntries.keySet(),
            "reversedRepaymentIds",
            activeRepayments.stream().map(PaybackRepayment::getId).toList()));
    unassignedEntries.forEach(
        (entryId, paycheckId) ->
            auditService.append(
                ownerId,
                "PAYCHECK_ENTRY",
                entryId,
                "PAYBACK_UNASSIGNED_DUE_TO_DELETION",
                now,
                Map.of("paybackId", paybackId),
                Map.of("paybackId", ""),
                Map.of("paybackId", paybackId, "paycheckId", paycheckId)));
  }

  @Transactional
  public PaybackListResponse reorder(UUID ownerId, ReorderPaybacksRequest request) {
    List<Payback> livePaybacks = paybacks.findAllByOwnerIdForUpdate(ownerId);
    Set<UUID> expected =
        livePaybacks.stream().map(Payback::getId).collect(HashSet::new, Set::add, Set::addAll);
    Set<UUID> supplied = new HashSet<>(request.paybackIds());
    if (supplied.size() != request.paybackIds().size() || !supplied.equals(expected)) {
      throw new BusinessRuleException(
          "PAYBACK_REORDER_INVALID",
          "Reorder must include every live Payback exactly once.",
          Map.of());
    }

    Map<UUID, Integer> before =
        livePaybacks.stream().collect(Collectors.toMap(Payback::getId, Payback::getPosition));
    int temporaryStart = livePaybacks.size();
    for (int index = 0; index < livePaybacks.size(); index++) {
      livePaybacks.get(index).moveTo(temporaryStart + index);
    }
    paybacks.saveAllAndFlush(livePaybacks);

    Map<UUID, Payback> byId =
        livePaybacks.stream().collect(Collectors.toMap(Payback::getId, payback -> payback));
    for (int index = 0; index < request.paybackIds().size(); index++) {
      byId.get(request.paybackIds().get(index)).moveTo(index);
    }
    paybacks.saveAllAndFlush(livePaybacks);
    auditService.append(
        ownerId,
        "PAYBACK",
        ownerId,
        "PAYBACKS_REORDERED",
        null,
        before,
        request.paybackIds(),
        null);
    return list(ownerId);
  }

  @Transactional(readOnly = true)
  public PageResponse<PaybackRepaymentResponse> repayments(
      UUID ownerId, UUID paybackId, int page, int size) {
    requirePayback(ownerId, paybackId);
    List<PaybackRepaymentResponse> rows =
        repayments.findAllByPaybackIdAndOwnerIdOrderByAppliedAtDesc(paybackId, ownerId).stream()
            .map(repayment -> toRepaymentResponse(ownerId, repayment))
            .toList();
    return paginate(rows, page, size);
  }

  @Transactional
  public void validateAssignment(UUID ownerId, PaycheckEntry entry, UUID paybackId) {
    if (paybackId == null) {
      return;
    }
    if (entry.getAmountMinor() <= 0) {
      throw new BusinessRuleException(
          "PAYBACK_REPAYMENT_AMOUNT_REQUIRED",
          "Payback repayments must be greater than $0.00.",
          Map.of());
    }
    Payback payback = requirePaybackForUpdate(ownerId, paybackId);
    if (payback.getState() != PaybackState.ACTIVE) {
      throw new BusinessRuleException(
          "PAYBACK_NOT_ACTIVE",
          "Choose an active Payback or remove the Payback assignment.",
          Map.of());
    }
    long remainingMinor = remainingMinor(payback);
    if (entry.getAmountMinor() > remainingMinor) {
      throw overpayment(entry.getAmountMinor() - remainingMinor);
    }
  }

  @Transactional
  public void syncAfterEntryUpdate(
      UUID ownerId,
      PaycheckEntry entry,
      UUID previousPaybackId,
      long previousAmountMinor,
      EntryStatus previousStatus,
      Instant recordedAt) {
    UUID nextPaybackId = entry.getPaybackId();
    boolean repaymentChanged =
        previousStatus == EntryStatus.POSTED
            && (previousAmountMinor != entry.getAmountMinor()
                || !sameId(previousPaybackId, nextPaybackId));
    if (repaymentChanged) {
      reversePostedEntryRepayment(ownerId, entry.getId(), recordedAt);
    }
    if (nextPaybackId != null && (previousStatus != EntryStatus.POSTED || repaymentChanged)) {
      validateAssignment(ownerId, entry, nextPaybackId);
    }
    if (entry.getStatus() == EntryStatus.POSTED
        && nextPaybackId != null
        && (previousStatus != EntryStatus.POSTED || repaymentChanged)) {
      applyPostedEntryRepayment(ownerId, entry, recordedAt);
    }
  }

  @Transactional
  public void applyPostedEntryRepayment(UUID ownerId, PaycheckEntry entry, Instant appliedAt) {
    UUID paybackId = entry.getPaybackId();
    if (paybackId == null) {
      return;
    }
    Payback payback =
        paybacks
            .findByIdAndOwnerIdForUpdate(paybackId, ownerId)
            .orElseThrow(ResourceNotFoundException::new);
    if (repayments.findByEntryIdAndOwnerIdAndReversedAtIsNull(entry.getId(), ownerId).isPresent()) {
      return;
    }
    if (payback.getState() != PaybackState.ACTIVE) {
      throw new BusinessRuleException(
          "PAYBACK_NOT_ACTIVE",
          "Choose an active Payback or remove the Payback assignment.",
          Map.of());
    }
    long remainingMinor = remainingMinor(payback);
    if (entry.getAmountMinor() > remainingMinor) {
      throw overpayment(entry.getAmountMinor() - remainingMinor);
    }
    repayments.saveAndFlush(
        new PaybackRepayment(
            ownerId, payback.getId(), entry.getId(), entry.getAmountMinor(), appliedAt));
    syncState(payback, appliedAt);
  }

  @Transactional
  public void reversePostedEntryRepayment(UUID ownerId, UUID entryId, Instant reversedAt) {
    repayments
        .findByEntryIdAndOwnerIdAndReversedAtIsNull(entryId, ownerId)
        .ifPresent(
            repayment -> {
              Payback payback =
                  paybacks
                      .findByIdAndOwnerIdForUpdate(repayment.getPaybackId(), ownerId)
                      .orElseThrow(ResourceNotFoundException::new);
              repayment.reverse(reversedAt);
              repayments.flush();
              syncState(payback, reversedAt);
            });
  }

  public Payback requirePayback(UUID ownerId, UUID paybackId) {
    return paybacks
        .findByIdAndOwnerIdAndDeletedAtIsNull(paybackId, ownerId)
        .orElseThrow(ResourceNotFoundException::new);
  }

  private Payback requirePaybackForUpdate(UUID ownerId, UUID paybackId) {
    return paybacks
        .findByIdAndOwnerIdForUpdate(paybackId, ownerId)
        .orElseThrow(ResourceNotFoundException::new);
  }

  private PaybackResponse toResponse(Payback payback) {
    return PaybackResponse.from(
        payback,
        activeRepaidMinor(payback),
        repayments.countByPaybackIdAndOwnerId(payback.getId(), payback.getOwnerId()));
  }

  private PaybackRepaymentResponse toRepaymentResponse(UUID ownerId, PaybackRepayment repayment) {
    PaycheckEntry entry =
        entries
            .findByIdAndOwnerId(repayment.getEntryId(), ownerId)
            .orElseThrow(ResourceNotFoundException::new);
    Paycheck paycheck =
        paychecks
            .findByIdAndOwnerId(entry.getPaycheckId(), ownerId)
            .orElseThrow(ResourceNotFoundException::new);
    return PaybackRepaymentResponse.from(repayment, entry, paycheck);
  }

  private void syncState(Payback payback, Instant recordedAt) {
    payback.recordRepaymentBalanceChange(remainingMinor(payback), recordedAt);
    paybacks.flush();
  }

  private long remainingMinor(Payback payback) {
    long remainingMinor = payback.getOpeningRemainingAmountMinor() - activeRepaidMinor(payback);
    if (remainingMinor < 0) {
      throw new BusinessRuleException(
          "PAYBACK_REMAINING_NEGATIVE",
          "Recorded repayments cannot exceed the balance when tracking began.",
          Map.of());
    }
    return remainingMinor;
  }

  private long activeRepaidMinor(Payback payback) {
    return repayments.sumActiveAmountByPaybackIdAndOwnerId(payback.getId(), payback.getOwnerId());
  }

  private void validateBaseline(long originalAmountMinor, long openingRemainingAmountMinor) {
    if (openingRemainingAmountMinor > originalAmountMinor) {
      throw new BusinessRuleException(
          "PAYBACK_OPENING_EXCEEDS_ORIGINAL",
          "Balance when tracking began cannot be greater than the original amount.",
          Map.of());
    }
  }

  private BusinessRuleException overpayment(long overageMinor) {
    return new BusinessRuleException(
        "PAYBACK_REPAYMENT_OVERPAID",
        "This repayment is more than the amount left on this Payback.",
        Map.of("amountMinor", overageMinor, "currencyCode", "USD"));
  }

  private void assertVersion(long actual, long supplied) {
    if (actual != supplied) {
      throw new ConflictException(
          "This record changed since it was loaded. Refresh and try again.");
    }
  }

  private String normalizeOptional(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private boolean sameId(UUID left, UUID right) {
    return left == null ? right == null : left.equals(right);
  }

  private <T> PageResponse<T> paginate(List<T> results, int page, int size) {
    int normalizedSize = Math.min(Math.max(size, 1), 100);
    int normalizedPage = Math.max(page, 0);
    int from = Math.min(normalizedPage * normalizedSize, results.size());
    int to = Math.min(from + normalizedSize, results.size());
    long totalItems = results.size();
    int totalPages =
        totalItems == 0 ? 0 : (int) Math.ceil((double) totalItems / (double) normalizedSize);
    return new PageResponse<>(
        results.subList(from, to),
        normalizedPage,
        normalizedSize,
        totalItems,
        totalPages,
        normalizedPage + 1 < totalPages);
  }
}
