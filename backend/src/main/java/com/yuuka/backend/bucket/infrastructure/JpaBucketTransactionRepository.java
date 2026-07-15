package com.yuuka.backend.bucket.infrastructure;

import com.yuuka.backend.bucket.domain.BucketTransaction;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaBucketTransactionRepository extends JpaRepository<BucketTransaction, UUID> {
  Optional<BucketTransaction> findByIdAndOwnerIdAndDeletedAtIsNull(UUID id, UUID ownerId);

  List<BucketTransaction>
      findAllByEntryIdAndOwnerIdAndDeletedAtIsNullOrderByEffectiveDateDescCreatedAtDesc(
          UUID entryId, UUID ownerId);

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
