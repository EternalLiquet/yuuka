package com.yuuka.backend.bucket.infrastructure;

import com.yuuka.backend.bucket.domain.BucketTransaction;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaBucketTransactionRepository extends JpaRepository<BucketTransaction, UUID> {
  Optional<BucketTransaction> findByIdAndOwnerIdAndDeletedAtIsNull(UUID id, UUID ownerId);

  List<BucketTransaction>
      findAllByEntryIdAndOwnerIdAndDeletedAtIsNullOrderByEffectiveDateDescCreatedAtDesc(
          UUID entryId, UUID ownerId);

  Page<BucketTransaction>
      findAllByEntryIdAndOwnerIdAndDeletedAtIsNullOrderByEffectiveDateDescCreatedAtDescIdDesc(
          UUID entryId, UUID ownerId, Pageable pageable);

  @Query(
      value =
          """
          select
              tx.entry_id as entryId,
              coalesce(sum(tx.amount_minor), 0) as spentMinor
          from bucket_transactions tx
          where tx.owner_id = :ownerId
            and tx.entry_id in (:entryIds)
            and tx.deleted_at is null
          group by tx.entry_id
          """,
      nativeQuery = true)
  List<BucketTransactionTotalProjection> aggregateSpentByEntryIds(
      @Param("ownerId") UUID ownerId, @Param("entryIds") java.util.Collection<UUID> entryIds);

  @Query(
      value =
          """
          with live_buckets as (
              select p.id as paycheck_id, e.id as entry_id, e.amount_minor
              from paychecks p
              join paycheck_entries e
                on e.paycheck_id = p.id
               and e.owner_id = p.owner_id
              where p.id = :paycheckId
                and p.owner_id = :ownerId
                and e.owner_id = :ownerId
                and e.deleted_at is null
                and e.entry_type = 'SPENDING_BUCKET'
          )
          select
              count(*) as bucketCount,
              count(distinct paycheck_id) as paycheckCount,
              coalesce(sum(amount_minor), 0) as budgetedMinor,
              coalesce((
                  select sum(t.amount_minor)
                  from bucket_transactions t
                  join live_buckets b on b.entry_id = t.entry_id
                  where t.owner_id = :ownerId
                    and t.deleted_at is null
                    and t.effective_date <= :asOfDate
              ), 0) as spentMinor
          from live_buckets
          """,
      nativeQuery = true)
  SpendingBucketPerformanceProjection aggregatePaycheckPerformance(
      @Param("ownerId") UUID ownerId,
      @Param("paycheckId") UUID paycheckId,
      @Param("asOfDate") LocalDate asOfDate);

  @Query(
      value =
          """
          with live_buckets as (
              select p.id as paycheck_id, e.id as entry_id, e.amount_minor
              from paychecks p
              join paycheck_entries e
                on e.paycheck_id = p.id
               and e.owner_id = p.owner_id
              where p.id in (:paycheckIds)
                and p.owner_id = :ownerId
                and e.owner_id = :ownerId
                and e.deleted_at is null
                and e.entry_type = 'SPENDING_BUCKET'
          ),
          transaction_totals as (
              select t.entry_id, coalesce(sum(t.amount_minor), 0) as spent_minor
              from bucket_transactions t
              where t.owner_id = :ownerId
                and t.deleted_at is null
                and t.effective_date <= :asOfDate
                and t.entry_id in (select entry_id from live_buckets)
              group by t.entry_id
          )
          select
              b.paycheck_id as paycheckId,
              count(*) as bucketCount,
              count(distinct b.paycheck_id) as paycheckCount,
              coalesce(sum(b.amount_minor), 0) as budgetedMinor,
              coalesce(sum(t.spent_minor), 0) as spentMinor
          from live_buckets b
          left join transaction_totals t on t.entry_id = b.entry_id
          group by b.paycheck_id
          """,
      nativeQuery = true)
  List<PaycheckSpendingBucketPerformanceProjection> aggregatePaycheckPerformanceByPaycheckIds(
      @Param("ownerId") UUID ownerId,
      @Param("paycheckIds") java.util.Collection<UUID> paycheckIds,
      @Param("asOfDate") LocalDate asOfDate);

  @Query(
      value =
          """
          with live_buckets as (
              select p.id as paycheck_id, e.id as entry_id, e.amount_minor
              from paychecks p
              join paycheck_entries e
                on e.paycheck_id = p.id
               and e.owner_id = p.owner_id
              where p.owner_id = :ownerId
                and e.owner_id = :ownerId
                and p.state in ('ACTIVE', 'CLOSED', 'ARCHIVED')
                and p.income_date between :windowStartDate and :asOfDate
                and e.deleted_at is null
                and e.entry_type = 'SPENDING_BUCKET'
          )
          select
              count(*) as bucketCount,
              count(distinct paycheck_id) as paycheckCount,
              coalesce(sum(amount_minor), 0) as budgetedMinor,
              coalesce((
                  select sum(t.amount_minor)
                  from bucket_transactions t
                  join live_buckets b on b.entry_id = t.entry_id
                  where t.owner_id = :ownerId
                    and t.deleted_at is null
                    and t.effective_date <= :asOfDate
              ), 0) as spentMinor
          from live_buckets
          """,
      nativeQuery = true)
  SpendingBucketPerformanceProjection aggregateRollingPerformance(
      @Param("ownerId") UUID ownerId,
      @Param("windowStartDate") LocalDate windowStartDate,
      @Param("asOfDate") LocalDate asOfDate);
}
