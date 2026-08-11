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

  @Query(
      value =
          """
          with qualifying_paychecks as (
              select p.id, p.name, p.income_date
              from paychecks p
              where p.owner_id = :ownerId
                and p.state in ('ACTIVE', 'CLOSED', 'ARCHIVED')
                and p.income_date <= :asOfDate
                and exists (
                    select 1
                    from paycheck_entries e
                    where e.paycheck_id = p.id
                      and e.owner_id = :ownerId
                      and e.deleted_at is null
                      and e.entry_type = 'SPENDING_BUCKET'
                )
              order by p.income_date desc, p.id desc
              limit :paycheckLimit
          ),
          live_buckets as (
              select
                  p.id as paycheck_id,
                  p.name as paycheck_name,
                  p.income_date,
                  e.id as entry_id,
                  e.name as entry_name,
                  lower(btrim(e.name)) as normalized_name,
                  e.amount_minor,
                  e.updated_at
              from qualifying_paychecks p
              join paycheck_entries e
                on e.paycheck_id = p.id
               and e.owner_id = :ownerId
               and e.deleted_at is null
               and e.entry_type = 'SPENDING_BUCKET'
          ),
          transaction_totals as (
              select t.entry_id, sum(t.amount_minor) as spent_minor
              from bucket_transactions t
              join live_buckets b on b.entry_id = t.entry_id
              where t.owner_id = :ownerId
                and t.deleted_at is null
                and t.effective_date <= :asOfDate
              group by t.entry_id
          ),
          latest_spelling as (
              select distinct on (normalized_name) normalized_name, btrim(entry_name) as display_name
              from live_buckets
              order by normalized_name, income_date desc, paycheck_id desc, updated_at desc, entry_id desc
          )
          select
              b.paycheck_id as paycheckId,
              b.paycheck_name as paycheckName,
              b.income_date as incomeDate,
              b.normalized_name as normalizedName,
              s.display_name as displayName,
              count(*) as matchingBucketCount,
              sum(b.amount_minor) as budgetedMinor,
              coalesce(sum(t.spent_minor), 0) as spentMinor
          from live_buckets b
          join latest_spelling s on s.normalized_name = b.normalized_name
          left join transaction_totals t on t.entry_id = b.entry_id
          group by b.paycheck_id, b.paycheck_name, b.income_date, b.normalized_name, s.display_name
          order by b.income_date asc, b.paycheck_id asc, b.normalized_name asc
          """,
      nativeQuery = true)
  List<SpendingBucketInsightProjection> findRecentInsightRows(
      @Param("ownerId") UUID ownerId,
      @Param("asOfDate") LocalDate asOfDate,
      @Param("paycheckLimit") int paycheckLimit);
}
