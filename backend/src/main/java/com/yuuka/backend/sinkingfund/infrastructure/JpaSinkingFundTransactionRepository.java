package com.yuuka.backend.sinkingfund.infrastructure;

import com.yuuka.backend.sinkingfund.domain.SinkingFundTransaction;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaSinkingFundTransactionRepository
    extends JpaRepository<SinkingFundTransaction, UUID> {
  @Query(
      value =
          """
          select
              tx.sinking_fund_id as sinkingFundId,
              coalesce(sum(
                  case
                      when tx.reversed_at is not null then 0
                      when tx.transaction_type in ('OPENING_BALANCE', 'CONTRIBUTION') then tx.amount_minor
                      when tx.transaction_type = 'WITHDRAWAL' then -tx.amount_minor
                      else 0
                  end
              ), 0) as currentBalanceMinor,
              count(*) as transactionCount
          from sinking_fund_transactions tx
          where tx.owner_id = :ownerId
            and tx.sinking_fund_id in (:fundIds)
          group by tx.sinking_fund_id
          """,
      nativeQuery = true)
  List<SinkingFundBalanceProjection> aggregateByFundIds(
      @Param("ownerId") UUID ownerId, @Param("fundIds") Collection<UUID> fundIds);

  @Query(
      value =
          """
          select coalesce(sum(
              case
                  when tx.reversed_at is not null then 0
                  when tx.transaction_type in ('OPENING_BALANCE', 'CONTRIBUTION') then tx.amount_minor
                  when tx.transaction_type = 'WITHDRAWAL' then -tx.amount_minor
                  else 0
              end
          ), 0)
          from sinking_fund_transactions tx
          where tx.owner_id = :ownerId
            and tx.sinking_fund_id = :fundId
          """,
      nativeQuery = true)
  BigDecimal currentBalanceMinor(@Param("ownerId") UUID ownerId, @Param("fundId") UUID fundId);

  long countBySinkingFundIdAndOwnerId(UUID sinkingFundId, UUID ownerId);

  Page<SinkingFundTransaction>
      findAllBySinkingFundIdAndOwnerIdOrderByEffectiveDateDescCreatedAtDescIdDesc(
          UUID sinkingFundId, UUID ownerId, Pageable pageable);

  Optional<SinkingFundTransaction> findByEntryIdAndOwnerIdAndReversedAtIsNull(
      UUID entryId, UUID ownerId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select tx from SinkingFundTransaction tx "
          + "where tx.entryId = :entryId and tx.ownerId = :ownerId and tx.reversedAt is null")
  Optional<SinkingFundTransaction> findActiveContributionByEntryIdForUpdate(
      @Param("entryId") UUID entryId, @Param("ownerId") UUID ownerId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select tx from SinkingFundTransaction tx " + "where tx.id = :id and tx.ownerId = :ownerId")
  Optional<SinkingFundTransaction> findByIdAndOwnerIdForUpdate(
      @Param("id") UUID id, @Param("ownerId") UUID ownerId);
}
