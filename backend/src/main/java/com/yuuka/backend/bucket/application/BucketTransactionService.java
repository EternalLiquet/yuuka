package com.yuuka.backend.bucket.application;

import com.yuuka.backend.audit.application.AuditService;
import com.yuuka.backend.bucket.api.dto.BucketTransactionResponse;
import com.yuuka.backend.bucket.api.dto.CreateBucketTransactionRequest;
import com.yuuka.backend.bucket.api.dto.UpdateBucketTransactionRequest;
import com.yuuka.backend.bucket.domain.BucketCalculator;
import com.yuuka.backend.bucket.domain.BucketTransaction;
import com.yuuka.backend.bucket.infrastructure.JpaBucketTransactionRepository;
import com.yuuka.backend.common.api.BusinessRuleException;
import com.yuuka.backend.common.api.ConflictException;
import com.yuuka.backend.common.api.ResourceNotFoundException;
import com.yuuka.backend.paycheck.domain.EntryType;
import com.yuuka.backend.paycheck.domain.Paycheck;
import com.yuuka.backend.paycheck.domain.PaycheckEntry;
import com.yuuka.backend.paycheck.domain.PaycheckState;
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckEntryRepository;
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BucketTransactionService {
  private final JpaBucketTransactionRepository transactions;
  private final JpaPaycheckEntryRepository entries;
  private final JpaPaycheckRepository paychecks;
  private final BucketCalculator bucketCalculator;
  private final AuditService auditService;
  private final Clock clock;

  public BucketTransactionService(
      JpaBucketTransactionRepository transactions,
      JpaPaycheckEntryRepository entries,
      JpaPaycheckRepository paychecks,
      BucketCalculator bucketCalculator,
      AuditService auditService,
      Clock clock) {
    this.transactions = transactions;
    this.entries = entries;
    this.paychecks = paychecks;
    this.bucketCalculator = bucketCalculator;
    this.auditService = auditService;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<BucketTransactionResponse> list(UUID ownerId, UUID entryId) {
    requireBucket(ownerId, entryId);
    return transactions
        .findAllByEntryIdAndOwnerIdAndDeletedAtIsNullOrderByEffectiveDateDescCreatedAtDesc(
            entryId, ownerId)
        .stream()
        .map(BucketTransactionResponse::from)
        .toList();
  }

  @Transactional
  public BucketTransactionResponse create(
      UUID ownerId, UUID entryId, CreateBucketTransactionRequest request) {
    PaycheckEntry entry = requireMutableBucketForUpdate(ownerId, entryId);
    rejectNonPositive(request.amountMinor());
    validateAggregateFitsInt64(entry, null, request.amountMinor());
    BucketTransaction transaction =
        transactions.saveAndFlush(
            new BucketTransaction(
                ownerId,
                entryId,
                request.amountMinor(),
                normalizeOptional(request.description()),
                normalizeOptional(request.notes()),
                request.effectiveDate()));
    touchPaycheck(ownerId, entry.getPaycheckId());
    BucketTransactionResponse response = BucketTransactionResponse.from(transaction);
    auditService.append(
        ownerId,
        "BUCKET_TRANSACTION",
        transaction.getId(),
        "CREATED",
        request.effectiveDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
        null,
        response,
        java.util.Map.of("entryId", entryId));
    return response;
  }

  @Transactional
  public BucketTransactionResponse update(
      UUID ownerId, UUID transactionId, UpdateBucketTransactionRequest request) {
    BucketTransaction transaction = requireTransaction(ownerId, transactionId);
    PaycheckEntry entry = requireMutableBucketForUpdate(ownerId, transaction.getEntryId());
    assertVersion(transaction.getVersion(), request.version());
    rejectNonPositive(request.amountMinor());
    validateAggregateFitsInt64(entry, transaction.getId(), request.amountMinor());
    BucketTransactionResponse before = BucketTransactionResponse.from(transaction);
    transaction.update(
        request.amountMinor(),
        normalizeOptional(request.description()),
        normalizeOptional(request.notes()),
        request.effectiveDate());
    transactions.flush();
    touchPaycheck(ownerId, entry.getPaycheckId());
    BucketTransactionResponse after = BucketTransactionResponse.from(transaction);
    auditService.append(
        ownerId,
        "BUCKET_TRANSACTION",
        transactionId,
        "UPDATED",
        request.effectiveDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
        before,
        after,
        null);
    return after;
  }

  @Transactional
  public void delete(UUID ownerId, UUID transactionId, long version) {
    BucketTransaction transaction = requireTransaction(ownerId, transactionId);
    PaycheckEntry entry = requireMutableBucket(ownerId, transaction.getEntryId());
    assertVersion(transaction.getVersion(), version);
    BucketTransactionResponse before = BucketTransactionResponse.from(transaction);
    Instant now = clock.instant();
    transaction.delete(now);
    touchPaycheck(ownerId, entry.getPaycheckId());
    auditService.append(
        ownerId, "BUCKET_TRANSACTION", transactionId, "DELETED", null, before, null, null);
  }

  private PaycheckEntry requireBucket(UUID ownerId, UUID entryId) {
    PaycheckEntry entry =
        entries
            .findByIdAndOwnerIdAndDeletedAtIsNull(entryId, ownerId)
            .orElseThrow(ResourceNotFoundException::new);
    if (entry.getEntryType() != EntryType.SPENDING_BUCKET) {
      throw new BusinessRuleException("Bucket transactions require a Spending Bucket entry.");
    }
    return entry;
  }

  private PaycheckEntry requireMutableBucket(UUID ownerId, UUID entryId) {
    PaycheckEntry entry = requireBucket(ownerId, entryId);
    Paycheck paycheck = requirePaycheck(ownerId, entry.getPaycheckId());
    if (paycheck.getState() != PaycheckState.ACTIVE) {
      throw new BusinessRuleException("Reopen the paycheck before changing it.");
    }
    return entry;
  }

  private PaycheckEntry requireMutableBucketForUpdate(UUID ownerId, UUID entryId) {
    PaycheckEntry entry =
        entries
            .findLiveByIdAndOwnerIdForUpdate(entryId, ownerId)
            .orElseThrow(ResourceNotFoundException::new);
    if (entry.getEntryType() != EntryType.SPENDING_BUCKET) {
      throw new BusinessRuleException("Bucket transactions require a Spending Bucket entry.");
    }
    Paycheck paycheck = requirePaycheck(ownerId, entry.getPaycheckId());
    if (paycheck.getState() != PaycheckState.ACTIVE) {
      throw new BusinessRuleException("Reopen the paycheck before changing it.");
    }
    return entry;
  }

  private BucketTransaction requireTransaction(UUID ownerId, UUID transactionId) {
    return transactions
        .findByIdAndOwnerIdAndDeletedAtIsNull(transactionId, ownerId)
        .orElseThrow(ResourceNotFoundException::new);
  }

  private Paycheck requirePaycheck(UUID ownerId, UUID paycheckId) {
    return paychecks
        .findByIdAndOwnerId(paycheckId, ownerId)
        .orElseThrow(ResourceNotFoundException::new);
  }

  private void touchPaycheck(UUID ownerId, UUID paycheckId) {
    requirePaycheck(ownerId, paycheckId).touch(clock.instant());
  }

  private void assertVersion(long actual, long supplied) {
    if (actual != supplied) {
      throw new ConflictException(
          "This record changed since it was loaded. Refresh and try again.");
    }
  }

  private void rejectNonPositive(long amountMinor) {
    if (amountMinor <= 0) {
      throw new BusinessRuleException("A bucket transaction must be a positive purchase amount.");
    }
  }

  private void validateAggregateFitsInt64(
      PaycheckEntry entry, UUID replacingTransactionId, long nextAmountMinor) {
    List<Long> amounts =
        transactions
            .findAllByEntryIdAndOwnerIdAndDeletedAtIsNullOrderByEffectiveDateDescCreatedAtDesc(
                entry.getId(), entry.getOwnerId())
            .stream()
            .filter(
                transaction ->
                    replacingTransactionId == null
                        || !transaction.getId().equals(replacingTransactionId))
            .map(BucketTransaction::getAmountMinor)
            .collect(Collectors.toCollection(ArrayList::new));
    amounts.add(nextAmountMinor);
    bucketCalculator.calculate(entry.getAmountMinor(), amounts);
  }

  private String normalizeOptional(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
