package com.yuuka.backend.sinkingfund.application;

import com.yuuka.backend.audit.application.AuditService;
import com.yuuka.backend.auth.application.OwnerLocalDateService;
import com.yuuka.backend.common.api.BusinessRuleException;
import com.yuuka.backend.common.api.ConflictException;
import com.yuuka.backend.common.api.MoneyArithmetic;
import com.yuuka.backend.common.api.PageResponse;
import com.yuuka.backend.common.api.ResourceNotFoundException;
import com.yuuka.backend.paycheck.domain.EntryStatus;
import com.yuuka.backend.paycheck.domain.EntryType;
import com.yuuka.backend.paycheck.domain.Paycheck;
import com.yuuka.backend.paycheck.domain.PaycheckEntry;
import com.yuuka.backend.paycheck.domain.PaycheckState;
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckEntryRepository;
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckRepository;
import com.yuuka.backend.sinkingfund.api.dto.AssignSinkingFundRequest;
import com.yuuka.backend.sinkingfund.api.dto.CreateSinkingFundRequest;
import com.yuuka.backend.sinkingfund.api.dto.CreateSinkingFundWithdrawalRequest;
import com.yuuka.backend.sinkingfund.api.dto.ReorderSinkingFundsRequest;
import com.yuuka.backend.sinkingfund.api.dto.ReverseSinkingFundWithdrawalRequest;
import com.yuuka.backend.sinkingfund.api.dto.SinkingFundListResponse;
import com.yuuka.backend.sinkingfund.api.dto.SinkingFundResponse;
import com.yuuka.backend.sinkingfund.api.dto.SinkingFundSummaryResponse;
import com.yuuka.backend.sinkingfund.api.dto.SinkingFundTransactionResponse;
import com.yuuka.backend.sinkingfund.api.dto.SinkingFundVersionRequest;
import com.yuuka.backend.sinkingfund.api.dto.UpdateSinkingFundRequest;
import com.yuuka.backend.sinkingfund.domain.SinkingFund;
import com.yuuka.backend.sinkingfund.domain.SinkingFundState;
import com.yuuka.backend.sinkingfund.domain.SinkingFundTransaction;
import com.yuuka.backend.sinkingfund.domain.SinkingFundTransactionType;
import com.yuuka.backend.sinkingfund.infrastructure.JpaSinkingFundRepository;
import com.yuuka.backend.sinkingfund.infrastructure.JpaSinkingFundTransactionRepository;
import com.yuuka.backend.sinkingfund.infrastructure.SinkingFundBalanceProjection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SinkingFundService {
  private final JpaSinkingFundRepository funds;
  private final JpaSinkingFundTransactionRepository transactions;
  private final JpaPaycheckEntryRepository entries;
  private final JpaPaycheckRepository paychecks;
  private final OwnerLocalDateService ownerLocalDateService;
  private final AuditService auditService;
  private final Clock clock;

  public SinkingFundService(
      JpaSinkingFundRepository funds,
      JpaSinkingFundTransactionRepository transactions,
      JpaPaycheckEntryRepository entries,
      JpaPaycheckRepository paychecks,
      OwnerLocalDateService ownerLocalDateService,
      AuditService auditService,
      Clock clock) {
    this.funds = funds;
    this.transactions = transactions;
    this.entries = entries;
    this.paychecks = paychecks;
    this.ownerLocalDateService = ownerLocalDateService;
    this.auditService = auditService;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public SinkingFundListResponse list(UUID ownerId, boolean includeArchived) {
    List<SinkingFund> items =
        includeArchived
            ? funds.findAllByOwnerIdOrderByStateAscPositionAscCreatedAtAscIdAsc(ownerId)
            : funds.findAllByOwnerIdAndStateOrderByPositionAscCreatedAtAscIdAsc(
                ownerId, SinkingFundState.ACTIVE);
    Map<UUID, Balance> balances =
        balances(ownerId, items.stream().map(SinkingFund::getId).toList());
    List<SinkingFundResponse> responses =
        items.stream()
            .sorted(
                Comparator.comparing(
                        (SinkingFund fund) -> fund.getState() != SinkingFundState.ACTIVE)
                    .thenComparingInt(SinkingFund::getPosition)
                    .thenComparing(SinkingFund::getCreatedAt)
                    .thenComparing(SinkingFund::getId))
            .map(fund -> toResponse(fund, balances.get(fund.getId())))
            .toList();
    long totalActive =
        MoneyArithmetic.sum(
            responses.stream()
                .filter(response -> response.state() == SinkingFundState.ACTIVE)
                .mapToLong(SinkingFundResponse::currentBalanceMinor));
    int activeCount =
        (int) responses.stream().filter(item -> item.state() == SinkingFundState.ACTIVE).count();
    int archivedCount =
        (int) responses.stream().filter(item -> item.state() == SinkingFundState.ARCHIVED).count();
    return new SinkingFundListResponse(
        new SinkingFundSummaryResponse(totalActive, activeCount, archivedCount), responses);
  }

  @Transactional(readOnly = true)
  public SinkingFundResponse get(UUID ownerId, UUID fundId) {
    return toResponse(requireFund(ownerId, fundId));
  }

  @Transactional
  public SinkingFundResponse create(UUID ownerId, CreateSinkingFundRequest request) {
    SinkingFund fund =
        funds.saveAndFlush(
            new SinkingFund(
                ownerId,
                request.name().trim(),
                request.targetMinor(),
                request.targetDate(),
                normalizeOptional(request.notes()),
                funds.findMaxActivePosition(ownerId) + 1));
    Long openingBalanceMinor = request.openingBalanceMinor();
    if (openingBalanceMinor != null && openingBalanceMinor > 0) {
      transactions.saveAndFlush(
          new SinkingFundTransaction(
              ownerId,
              fund.getId(),
              null,
              SinkingFundTransactionType.OPENING_BALANCE,
              openingBalanceMinor,
              ownerLocalDateService.currentDate(ownerId),
              "Opening balance",
              null));
    }
    SinkingFundResponse response = toResponse(fund);
    auditService.append(
        ownerId, "SINKING_FUND", fund.getId(), "CREATED", null, null, response, null);
    return response;
  }

  @Transactional
  public SinkingFundResponse update(UUID ownerId, UUID fundId, UpdateSinkingFundRequest request) {
    SinkingFund fund = requireFundForUpdate(ownerId, fundId);
    assertVersion(fund.getVersion(), request.version());
    SinkingFundResponse before = toResponse(fund);
    fund.update(
        request.name().trim(),
        request.targetMinor(),
        request.targetDate(),
        normalizeOptional(request.notes()));
    funds.flush();
    SinkingFundResponse after = toResponse(fund);
    auditService.append(ownerId, "SINKING_FUND", fundId, "UPDATED", null, before, after, null);
    return after;
  }

  @Transactional
  public SinkingFundResponse archive(UUID ownerId, UUID fundId, SinkingFundVersionRequest request) {
    SinkingFund fund = requireFundForUpdate(ownerId, fundId);
    assertVersion(fund.getVersion(), request.version());
    if (fund.getState() == SinkingFundState.ARCHIVED) {
      throw new BusinessRuleException("The Sinking Fund is already archived.");
    }
    long pendingLinkedEntries =
        entries.countLivePendingBySinkingFundIdAndOwnerId(fundId, ownerId, EntryStatus.POSTED);
    if (pendingLinkedEntries > 0) {
      throw new BusinessRuleException(
          "SINKING_FUND_ARCHIVE_HAS_PENDING_ENTRIES",
          "Post or unlink pending entries before archiving this Sinking Fund.",
          Map.of());
    }
    long balance = currentBalance(ownerId, fundId);
    if (balance > 0 && !request.confirmPositiveBalance()) {
      throw new BusinessRuleException(
          "SINKING_FUND_ARCHIVE_BALANCE_CONFIRMATION_REQUIRED",
          "Confirm that this Sinking Fund should be archived with money still reserved.",
          Map.of("amountMinor", balance, "currencyCode", "USD"));
    }
    SinkingFundResponse before = toResponse(fund, new Balance(balance, transactionCount(fund)));
    Instant now = clock.instant();
    fund.archive(now);
    funds.flush();
    SinkingFundResponse after = toResponse(fund, new Balance(balance, transactionCount(fund)));
    auditService.append(ownerId, "SINKING_FUND", fundId, "ARCHIVED", now, before, after, null);
    return after;
  }

  @Transactional
  public SinkingFundResponse restore(UUID ownerId, UUID fundId, SinkingFundVersionRequest request) {
    SinkingFund fund = requireFundForUpdate(ownerId, fundId);
    assertVersion(fund.getVersion(), request.version());
    if (fund.getState() == SinkingFundState.ACTIVE) {
      throw new BusinessRuleException("The Sinking Fund is already active.");
    }
    SinkingFundResponse before = toResponse(fund);
    Instant now = clock.instant();
    fund.restore(now);
    funds.flush();
    SinkingFundResponse after = toResponse(fund);
    auditService.append(ownerId, "SINKING_FUND", fundId, "RESTORED", now, before, after, null);
    return after;
  }

  @Transactional
  public SinkingFundListResponse reorder(UUID ownerId, ReorderSinkingFundsRequest request) {
    List<SinkingFund> activeFunds = funds.findActiveByOwnerIdForUpdate(ownerId);
    Set<UUID> expected =
        activeFunds.stream().map(SinkingFund::getId).collect(HashSet::new, Set::add, Set::addAll);
    Set<UUID> supplied = new HashSet<>(request.sinkingFundIds());
    if (supplied.size() != request.sinkingFundIds().size() || !supplied.equals(expected)) {
      throw new BusinessRuleException(
          "SINKING_FUND_REORDER_INVALID",
          "Reorder must include every active Sinking Fund exactly once.",
          Map.of());
    }
    Map<UUID, Integer> before =
        activeFunds.stream()
            .collect(Collectors.toMap(SinkingFund::getId, SinkingFund::getPosition));
    int temporaryStart = activeFunds.size();
    for (int index = 0; index < activeFunds.size(); index++) {
      activeFunds.get(index).moveTo(temporaryStart + index);
    }
    funds.saveAllAndFlush(activeFunds);
    Map<UUID, SinkingFund> byId =
        activeFunds.stream().collect(Collectors.toMap(SinkingFund::getId, Function.identity()));
    for (int index = 0; index < request.sinkingFundIds().size(); index++) {
      byId.get(request.sinkingFundIds().get(index)).moveTo(index);
    }
    funds.saveAllAndFlush(activeFunds);
    auditService.append(
        ownerId,
        "SINKING_FUND",
        ownerId,
        "SINKING_FUNDS_REORDERED",
        null,
        before,
        request.sinkingFundIds(),
        null);
    return list(ownerId, true);
  }

  @Transactional(readOnly = true)
  public PageResponse<SinkingFundTransactionResponse> transactions(
      UUID ownerId, UUID fundId, int page, int size) {
    requireFund(ownerId, fundId);
    return PageResponse.from(
        transactions
            .findAllBySinkingFundIdAndOwnerIdOrderByEffectiveDateDescCreatedAtDescIdDesc(
                fundId, ownerId, listPage(page, size))
            .map(transaction -> toTransactionResponse(ownerId, transaction)));
  }

  @Transactional
  public SinkingFundTransactionResponse withdraw(
      UUID ownerId, UUID fundId, CreateSinkingFundWithdrawalRequest request) {
    SinkingFund fund = requireFundForUpdate(ownerId, fundId);
    assertVersion(fund.getVersion(), request.version());
    requireActive(fund);
    long balance = currentBalance(ownerId, fundId);
    if (request.amountMinor() > balance) {
      throw new BusinessRuleException(
          "SINKING_FUND_WITHDRAWAL_EXCEEDS_BALANCE",
          "Withdrawal cannot exceed the current Sinking Fund balance.",
          Map.of("amountMinor", request.amountMinor() - balance, "currencyCode", "USD"));
    }
    LocalDate effectiveDate =
        request.effectiveDate() == null
            ? ownerLocalDateService.currentDate(ownerId)
            : request.effectiveDate();
    SinkingFundTransaction transaction =
        transactions.saveAndFlush(
            new SinkingFundTransaction(
                ownerId,
                fundId,
                null,
                SinkingFundTransactionType.WITHDRAWAL,
                request.amountMinor(),
                effectiveDate,
                request.reason().trim(),
                normalizeOptional(request.notes())));
    fund.touch(clock.instant());
    funds.flush();
    SinkingFundTransactionResponse response = toTransactionResponse(ownerId, transaction);
    auditService.append(
        ownerId, "SINKING_FUND", fundId, "WITHDRAWAL_CREATED", null, null, response, null);
    return response;
  }

  @Transactional
  public SinkingFundTransactionResponse reverseWithdrawal(
      UUID ownerId, UUID transactionId, ReverseSinkingFundWithdrawalRequest request) {
    SinkingFundTransaction transaction =
        transactions
            .findByIdAndOwnerIdForUpdate(transactionId, ownerId)
            .orElseThrow(ResourceNotFoundException::new);
    if (transaction.getTransactionType() != SinkingFundTransactionType.WITHDRAWAL) {
      throw new BusinessRuleException("Only withdrawals can be reversed from this workflow.");
    }
    assertVersion(transaction.getVersion(), request.version());
    if (transaction.getReversedAt() != null) {
      throw new BusinessRuleException("This withdrawal has already been reversed.");
    }
    SinkingFund fund = requireFundForUpdate(ownerId, transaction.getSinkingFundId());
    Instant now = clock.instant();
    transaction.reverse(now, request.reason().trim());
    transactions.flush();
    fund.touch(now);
    funds.flush();
    SinkingFundTransactionResponse response = toTransactionResponse(ownerId, transaction);
    auditService.append(
        ownerId,
        "SINKING_FUND",
        fund.getId(),
        "WITHDRAWAL_REVERSED",
        now,
        null,
        response,
        Map.of("transactionId", transactionId));
    return response;
  }

  @Transactional
  public void validateAssignment(UUID ownerId, PaycheckEntry entry, UUID fundId) {
    if (fundId == null) {
      return;
    }
    if (entry.getEntryType() != EntryType.SINKING_FUND) {
      throw new BusinessRuleException("Only Sinking Fund entries can link to a Sinking Fund.");
    }
    if (entry.getAmountMinor() <= 0) {
      throw new BusinessRuleException(
          "SINKING_FUND_CONTRIBUTION_AMOUNT_REQUIRED",
          "Linked Sinking Fund contributions must be greater than $0.00.",
          Map.of());
    }
    SinkingFund fund = requireFundForUpdate(ownerId, fundId);
    requireActive(fund);
  }

  @Transactional
  public void syncAfterEntryUpdate(
      UUID ownerId,
      PaycheckEntry entry,
      UUID previousFundId,
      long previousAmountMinor,
      EntryStatus previousStatus,
      Instant recordedAt) {
    UUID nextFundId = entry.getSinkingFundId();
    boolean contributionChanged =
        previousStatus == EntryStatus.POSTED
            && (previousAmountMinor != entry.getAmountMinor()
                || !sameId(previousFundId, nextFundId));
    if (contributionChanged) {
      reversePostedEntryContribution(ownerId, entry.getId(), recordedAt);
    }
    if (nextFundId != null && (previousStatus != EntryStatus.POSTED || contributionChanged)) {
      validateAssignment(ownerId, entry, nextFundId);
    }
    if (entry.getStatus() == EntryStatus.POSTED
        && nextFundId != null
        && (previousStatus != EntryStatus.POSTED || contributionChanged)) {
      applyPostedEntryContribution(ownerId, entry, recordedAt);
    }
  }

  @Transactional
  public void applyPostedEntryContribution(UUID ownerId, PaycheckEntry entry, Instant recordedAt) {
    UUID fundId = entry.getSinkingFundId();
    if (fundId == null) {
      return;
    }
    SinkingFund fund = requireFundForUpdate(ownerId, fundId);
    if (transactions.findActiveContributionByEntryIdForUpdate(entry.getId(), ownerId).isPresent()) {
      return;
    }
    validateAssignment(ownerId, entry, fundId);
    transactions.saveAndFlush(
        new SinkingFundTransaction(
            ownerId,
            fund.getId(),
            entry.getId(),
            SinkingFundTransactionType.CONTRIBUTION,
            entry.getAmountMinor(),
            ownerLocalDateService.currentDate(ownerId),
            null,
            null));
    fund.touch(recordedAt);
    funds.flush();
  }

  @Transactional
  public void reversePostedEntryContribution(UUID ownerId, UUID entryId, Instant reversedAt) {
    transactions
        .findActiveContributionByEntryIdForUpdate(entryId, ownerId)
        .ifPresent(
            transaction -> {
              SinkingFund fund = requireFundForUpdate(ownerId, transaction.getSinkingFundId());
              transaction.reverse(reversedAt, null);
              transactions.flush();
              fund.touch(reversedAt);
              funds.flush();
            });
  }

  @Transactional
  public void assignEntry(UUID ownerId, UUID entryId, AssignSinkingFundRequest request) {
    PaycheckEntry entry =
        entries
            .findLiveByIdAndOwnerIdForUpdate(entryId, ownerId)
            .orElseThrow(ResourceNotFoundException::new);
    Paycheck paycheck =
        paychecks
            .findByIdAndOwnerIdForUpdate(entry.getPaycheckId(), ownerId)
            .orElseThrow(ResourceNotFoundException::new);
    if (paycheck.getState() != PaycheckState.ACTIVE) {
      throw new BusinessRuleException("Only Active paycheck entries can be assigned.");
    }
    assertVersion(entry.getVersion(), request.version());
    UUID previousFundId = entry.getSinkingFundId();
    long previousAmountMinor = entry.getAmountMinor();
    EntryStatus previousStatus = entry.getStatus();
    Instant now = clock.instant();
    entry.assignSinkingFund(request.sinkingFundId());
    validateAssignment(ownerId, entry, request.sinkingFundId());
    syncAfterEntryUpdate(ownerId, entry, previousFundId, previousAmountMinor, previousStatus, now);
    entries.flush();
    paycheck.touch(now);
    paychecks.flush();
  }

  public SinkingFund requireFund(UUID ownerId, UUID fundId) {
    return funds.findByIdAndOwnerId(fundId, ownerId).orElseThrow(ResourceNotFoundException::new);
  }

  private SinkingFund requireFundForUpdate(UUID ownerId, UUID fundId) {
    return funds
        .findByIdAndOwnerIdForUpdate(fundId, ownerId)
        .orElseThrow(ResourceNotFoundException::new);
  }

  private void requireActive(SinkingFund fund) {
    if (fund.getState() != SinkingFundState.ACTIVE) {
      throw new BusinessRuleException(
          "SINKING_FUND_NOT_ACTIVE",
          "Choose an active Sinking Fund or remove the Sinking Fund assignment.",
          Map.of());
    }
  }

  private SinkingFundResponse toResponse(SinkingFund fund) {
    return toResponse(
        fund, new Balance(currentBalance(fund.getOwnerId(), fund.getId()), transactionCount(fund)));
  }

  private SinkingFundResponse toResponse(SinkingFund fund, Balance balance) {
    Balance effective = balance == null ? Balance.ZERO : balance;
    return SinkingFundResponse.from(
        fund, effective.currentBalanceMinor(), effective.transactionCount());
  }

  private SinkingFundTransactionResponse toTransactionResponse(
      UUID ownerId, SinkingFundTransaction transaction) {
    if (transaction.getEntryId() == null) {
      return SinkingFundTransactionResponse.from(transaction, null, null);
    }
    PaycheckEntry entry =
        entries
            .findByIdAndOwnerId(transaction.getEntryId(), ownerId)
            .orElseThrow(ResourceNotFoundException::new);
    Paycheck paycheck =
        paychecks
            .findByIdAndOwnerId(entry.getPaycheckId(), ownerId)
            .orElseThrow(ResourceNotFoundException::new);
    return SinkingFundTransactionResponse.from(transaction, entry, paycheck);
  }

  private Map<UUID, Balance> balances(UUID ownerId, List<UUID> fundIds) {
    if (fundIds.isEmpty()) {
      return Map.of();
    }
    return transactions.aggregateByFundIds(ownerId, fundIds).stream()
        .collect(
            Collectors.toMap(
                SinkingFundBalanceProjection::getSinkingFundId,
                projection ->
                    new Balance(
                        value(projection.getCurrentBalanceMinor()),
                        value(projection.getTransactionCount()))));
  }

  private long currentBalance(UUID ownerId, UUID fundId) {
    return transactions.currentBalanceMinor(ownerId, fundId);
  }

  private long transactionCount(SinkingFund fund) {
    return transactions.countBySinkingFundIdAndOwnerId(fund.getId(), fund.getOwnerId());
  }

  private void assertVersion(long actual, long expected) {
    if (actual != expected) {
      throw new ConflictException("Resource was updated. Refresh and try again.");
    }
  }

  private PageRequest listPage(int requestedPage, int requestedSize) {
    return PageRequest.of(Math.max(0, requestedPage), Math.min(Math.max(requestedSize, 1), 100));
  }

  private String normalizeOptional(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private boolean sameId(UUID left, UUID right) {
    return left == null ? right == null : left.equals(right);
  }

  private long value(Long value) {
    return value == null ? 0 : value;
  }

  private record Balance(long currentBalanceMinor, long transactionCount) {
    private static final Balance ZERO = new Balance(0, 0);
  }
}
