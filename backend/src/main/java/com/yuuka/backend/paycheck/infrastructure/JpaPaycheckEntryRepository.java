package com.yuuka.backend.paycheck.infrastructure;

import com.yuuka.backend.paycheck.domain.PaycheckEntry;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaPaycheckEntryRepository extends JpaRepository<PaycheckEntry, UUID> {
  Optional<PaycheckEntry> findByIdAndOwnerId(UUID id, UUID ownerId);

  Optional<PaycheckEntry> findByIdAndOwnerIdAndDeletedAtIsNull(UUID id, UUID ownerId);

  List<PaycheckEntry> findAllByPaycheckIdAndOwnerIdAndDeletedAtIsNullOrderByPosition(
      UUID paycheckId, UUID ownerId);

  @Query(
      "select entry from PaycheckEntry entry "
          + "where entry.paycheckId in :paycheckIds and entry.ownerId = :ownerId "
          + "and entry.deletedAt is null "
          + "order by entry.paycheckId, entry.position, entry.id")
  List<PaycheckEntry> findAllLiveByPaycheckIds(
      @Param("ownerId") UUID ownerId, @Param("paycheckIds") java.util.Collection<UUID> paycheckIds);

  @Query(
      value =
          """
          select
              entry.paycheck_id as paycheckId,
              coalesce(sum(entry.amount_minor), 0) as allocatedMinor,
              coalesce(sum(case when entry.status = 'POSTED' then entry.amount_minor else 0 end), 0) as postedMinor,
              coalesce(sum(case when entry.status = 'PROCESSING' then entry.amount_minor else 0 end), 0) as processingMinor,
              coalesce(sum(case when entry.status = 'NOT_PAID' then entry.amount_minor else 0 end), 0) as notPaidMinor,
              coalesce(sum(case when entry.status = 'POSTED' then 1 else 0 end), 0) as postedCount,
              coalesce(sum(case when entry.status = 'PROCESSING' then 1 else 0 end), 0) as processingCount,
              coalesce(sum(case when entry.status = 'NOT_PAID' then 1 else 0 end), 0) as notPaidCount
          from paycheck_entries entry
          where entry.owner_id = :ownerId
            and entry.paycheck_id in (:paycheckIds)
            and entry.deleted_at is null
          group by entry.paycheck_id
          """,
      nativeQuery = true)
  List<PaycheckMetricsProjection> aggregateMetricsByPaycheckIds(
      @Param("ownerId") UUID ownerId, @Param("paycheckIds") java.util.Collection<UUID> paycheckIds);

  @Query(
      "select coalesce(max(entry.position), -1) from PaycheckEntry entry "
          + "where entry.paycheckId = :paycheckId and entry.deletedAt is null")
  int findMaxLivePosition(@Param("paycheckId") UUID paycheckId);

  long countByPaycheckIdAndOwnerIdAndDeletedAtIsNull(UUID paycheckId, UUID ownerId);

  long countByPaybackIdAndOwnerIdAndDeletedAtIsNull(UUID paybackId, UUID ownerId);

  @Query(
      "select entry from PaycheckEntry entry "
          + "where entry.ownerId = :ownerId "
          + "and entry.sourceRecurringBillDefinitionId in :definitionIds "
          + "and entry.sourceRecurringOccurrenceDate between :from and :through "
          + "and entry.deletedAt is null")
  List<PaycheckEntry> findRecurringImports(
      @Param("ownerId") UUID ownerId,
      @Param("definitionIds") java.util.Collection<UUID> definitionIds,
      @Param("from") java.time.LocalDate from,
      @Param("through") java.time.LocalDate through);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select entry from PaycheckEntry entry "
          + "where entry.paybackId = :paybackId and entry.ownerId = :ownerId "
          + "and entry.deletedAt is null "
          + "order by entry.paycheckId, entry.position, entry.id")
  List<PaycheckEntry> findLiveAssignedToPaybackForUpdate(
      @Param("paybackId") UUID paybackId, @Param("ownerId") UUID ownerId);
}
