package com.yuuka.backend.expense.infrastructure;

import com.yuuka.backend.expense.domain.ExpenseLedgerItem;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaExpenseLedgerItemRepository extends JpaRepository<ExpenseLedgerItem, UUID> {
  Optional<ExpenseLedgerItem> findByIdAndOwnerIdAndDeletedAtIsNull(UUID id, UUID ownerId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select item from ExpenseLedgerItem item "
          + "where item.id = :id and item.ownerId = :ownerId and item.deletedAt is null")
  Optional<ExpenseLedgerItem> findByIdAndOwnerIdForUpdate(
      @Param("id") UUID id, @Param("ownerId") UUID ownerId);

  List<ExpenseLedgerItem>
      findAllByLedgerIdAndOwnerIdAndDeletedAtIsNullOrderByExpenseDateDescCreatedAtDescIdDesc(
          UUID ledgerId, UUID ownerId);

  @Query(
      value =
          """
          select
            item.ledger_id as ledgerId,
            coalesce(sum(item.amount_minor), 0) as totalMinor,
            count(item.id) as itemCount,
            max(item.expense_date) as latestExpenseDate
          from expense_ledger_items item
          where item.owner_id = :ownerId
            and item.ledger_id = :ledgerId
            and item.deleted_at is null
          group by item.ledger_id
          """,
      nativeQuery = true)
  Optional<ExpenseLedgerTotalsProjection> totalsByLedgerId(
      @Param("ownerId") UUID ownerId, @Param("ledgerId") UUID ledgerId);

  @Query(
      value =
          """
          select
            item.ledger_id as ledgerId,
            coalesce(sum(item.amount_minor), 0) as totalMinor,
            count(item.id) as itemCount,
            max(item.expense_date) as latestExpenseDate
          from expense_ledger_items item
          where item.owner_id = :ownerId
            and item.ledger_id in (:ledgerIds)
            and item.deleted_at is null
          group by item.ledger_id
          """,
      nativeQuery = true)
  List<ExpenseLedgerTotalsProjection> totalsByLedgerIds(
      @Param("ownerId") UUID ownerId, @Param("ledgerIds") Collection<UUID> ledgerIds);
}
