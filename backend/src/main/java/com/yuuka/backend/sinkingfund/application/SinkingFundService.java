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
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckEntryRepository;
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckRepository;
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
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    var results =
        transactions.findAllBySinkingFundIdAndOwnerIdOrderByEffectiveDateDescCreatedAtDescIdDesc(
            fundId, ownerId, listPage(page, size));
    Map<UUID, PaycheckEntry> entriesById = entriesById(ownerId, results.getContent());
    Map<UUID, Paycheck> paychecksById =
        paychecksById(ownerId, entriesById.values().stream().toList());
    return PageResponse.from(
        results.map(transaction -> toTransactionResponse(transaction, entriesById, paychecksById)));
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
    MoneyArithmetic.add(currentBalance(ownerId, fund.getId()), transaction.getAmountMinor());
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
    validateAssignment(ownerId, entry, fundId, null);
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
    if (contributionChanged && previousFundId != null && sameId(previousFundId, nextFundId)) {
      replacePostedEntryContribution(ownerId, entry, recordedAt);
      return;
    }
    Map<UUID, SinkingFund> lockedFunds =
        contributionChanged ? lockFundsForUpdate(ownerId, previousFundId, nextFundId) : Map.of();
    if (contributionChanged) {
      reversePostedEntryContribution(
          ownerId, entry.getId(), recordedAt, lockedFunds.get(previousFundId));
    }
    if (nextFundId != null && (previousStatus != EntryStatus.POSTED || contributionChanged)) {
      validateAssignment(ownerId, entry, nextFundId, lockedFunds.get(nextFundId));
    }
    if (entry.getStatus() == EntryStatus.POSTED
        && nextFundId != null
        && (previousStatus != EntryStatus.POSTED || contributionChanged)) {
      applyPostedEntryContribution(ownerId, entry, recordedAt, lockedFunds.get(nextFundId));
    }
  }

  @Transactional
  public void applyPostedEntryContribution(UUID ownerId, PaycheckEntry entry, Instant recordedAt) {
    UUID fundId = entry.getSinkingFundId();
    if (fundId == null) {
      return;
    }
    SinkingFund fund = requireFundForUpdate(ownerId, fundId);
    applyPostedEntryContribution(ownerId, entry, recordedAt, fund);
  }

  private void applyPostedEntryContribution(
      UUID ownerId, PaycheckEntry entry, Instant recordedAt, SinkingFund fund) {
    if (transactions.findActiveContributionByEntryIdForUpdate(entry.getId(), ownerId).isPresent()) {
      return;
    }
    validateAssignment(ownerId, entry, fund.getId(), fund);
    MoneyArithmetic.add(currentBalance(ownerId, fund.getId()), entry.getAmountMinor());
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
    reversePostedEntryContribution(ownerId, entryId, reversedAt, null);
  }

  private void reversePostedEntryContribution(
      UUID ownerId, UUID entryId, Instant reversedAt, SinkingFund lockedFund) {
    transactions
        .findActiveContributionByEntryIdForUpdate(entryId, ownerId)
        .ifPresent(
            transaction -> {
              SinkingFund fund =
                  lockedFund == null
                      ? requireFundForUpdate(ownerId, transaction.getSinkingFundId())
                      : lockedFund;
              assertContributionCanBeReversed(ownerId, fund.getId(), transaction.getAmountMinor());
              transaction.reverse(reversedAt, null);
              transactions.flush();
              fund.touch(reversedAt);
              funds.flush();
            });
  }

  private void replacePostedEntryContribution(
      UUID ownerId, PaycheckEntry entry, Instant recordedAt) {
    SinkingFund fund = requireFundForUpdate(ownerId, entry.getSinkingFundId());
    transactions
        .findActiveContributionByEntryIdForUpdate(entry.getId(), ownerId)
        .ifPresent(
            transaction -> {
              validateAssignment(ownerId, entry, fund.getId(), fund);
              long balanceAfterOldContribution =
                  MoneyArithmetic.subtract(
                      currentBalance(ownerId, fund.getId()), transaction.getAmountMinor());
              long finalBalance =
                  MoneyArithmetic.add(balanceAfterOldContribution, entry.getAmountMinor());
              if (finalBalance < 0) {
                throwContributionReversalExceedsBalance(-finalBalance);
              }
              transaction.reverse(recordedAt, null);
              transactions.flush();
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
            });
  }

  public SinkingFund requireFund(UUID ownerId, UUID fundId) {
    return funds.findByIdAndOwnerId(fundId, ownerId).orElseThrow(ResourceNotFoundException::new);
  }

  private SinkingFund requireFundForUpdate(UUID ownerId, UUID fundId) {
    return funds
        .findByIdAndOwnerIdForUpdate(fundId, ownerId)
        .orElseThrow(ResourceNotFoundException::new);
  }

  private void validateAssignment(
      UUID ownerId, PaycheckEntry entry, UUID fundId, SinkingFund lockedFund) {
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
    SinkingFund fund = lockedFund == null ? requireFundForUpdate(ownerId, fundId) : lockedFund;
    requireActive(fund);
  }

  private Map<UUID, SinkingFund> lockFundsForUpdate(UUID ownerId, UUID firstId, UUID secondId) {
    List<UUID> fundIds =
        java.util.stream.Stream.of(firstId, secondId)
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
    if (fundIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, SinkingFund> locked = new LinkedHashMap<>();
    for (UUID fundId : fundIds) {
      locked.put(fundId, requireFundForUpdate(ownerId, fundId));
    }
    return locked;
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
      SinkingFundTransaction transaction,
      Map<UUID, PaycheckEntry> entriesById,
      Map<UUID, Paycheck> paychecksById) {
    if (transaction.getEntryId() == null) {
      return SinkingFundTransactionResponse.from(transaction, null, null);
    }
    PaycheckEntry entry = entriesById.get(transaction.getEntryId());
    if (entry == null) {
      return SinkingFundTransactionResponse.from(transaction, null, null);
    }
    Paycheck paycheck = paychecksById.get(entry.getPaycheckId());
    return SinkingFundTransactionResponse.from(transaction, entry, paycheck);
  }

  private SinkingFundTransactionResponse toTransactionResponse(
      UUID ownerId, SinkingFundTransaction transaction) {
    Map<UUID, PaycheckEntry> entriesById = entriesById(ownerId, List.of(transaction));
    Map<UUID, Paycheck> paychecksById =
        paychecksById(ownerId, entriesById.values().stream().toList());
    return toTransactionResponse(transaction, entriesById, paychecksById);
  }

  private Map<UUID, PaycheckEntry> entriesById(
      UUID ownerId, List<SinkingFundTransaction> transactionsPage) {
    List<UUID> entryIds =
        transactionsPage.stream()
            .map(SinkingFundTransaction::getEntryId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    if (entryIds.isEmpty()) {
      return Map.of();
    }
    return entries.findAllByIdInAndOwnerId(entryIds, ownerId).stream()
        .collect(Collectors.toMap(PaycheckEntry::getId, Function.identity()));
  }

  private Map<UUID, Paycheck> paychecksById(UUID ownerId, List<PaycheckEntry> entriesPage) {
    List<UUID> paycheckIds =
        entriesPage.stream().map(PaycheckEntry::getPaycheckId).distinct().toList();
    if (paycheckIds.isEmpty()) {
      return Map.of();
    }
    return paychecks.findAllByIdInAndOwnerId(paycheckIds, ownerId).stream()
        .collect(Collectors.toMap(Paycheck::getId, Function.identity()));
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
    return MoneyArithmetic.toLongExact(transactions.currentBalanceMinor(ownerId, fundId));
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

  private long value(BigDecimal value) {
    return MoneyArithmetic.toLongExact(value);
  }

  private void assertContributionCanBeReversed(UUID ownerId, UUID fundId, long amountMinor) {
    long balance = currentBalance(ownerId, fundId);
    if (amountMinor > balance) {
      throwContributionReversalExceedsBalance(amountMinor - balance);
    }
  }

  private void throwContributionReversalExceedsBalance(long shortfallMinor) {
    throw new BusinessRuleException(
        "SINKING_FUND_CONTRIBUTION_REVERSAL_EXCEEDS_BALANCE",
        "This contribution cannot be reversed because withdrawals have already used it.",
        Map.of("amountMinor", shortfallMinor, "currencyCode", "USD"));
  }

  private record Balance(long currentBalanceMinor, long transactionCount) {
    private static final Balance ZERO = new Balance(0, 0);
  }
}
