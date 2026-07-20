package com.yuuka.backend.expense.infrastructure;

import com.yuuka.backend.expense.domain.ExpenseLedger;
import com.yuuka.backend.expense.domain.ExpenseLedgerState;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaExpenseLedgerRepository extends JpaRepository<ExpenseLedger, UUID> {
  Optional<ExpenseLedger> findByIdAndOwnerIdAndDeletedAtIsNull(UUID id, UUID ownerId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select ledger from ExpenseLedger ledger "
          + "where ledger.id = :id and ledger.ownerId = :ownerId and ledger.deletedAt is null")
  Optional<ExpenseLedger> findByIdAndOwnerIdForUpdate(
      @Param("id") UUID id, @Param("ownerId") UUID ownerId);

  Page<ExpenseLedger> findAllByOwnerIdAndDeletedAtIsNull(UUID ownerId, Pageable pageable);

  Page<ExpenseLedger> findAllByOwnerIdAndStateAndDeletedAtIsNull(
      UUID ownerId, ExpenseLedgerState state, Pageable pageable);

  List<ExpenseLedger> findAllByOwnerIdAndStateAndDeletedAtIsNullOrderByFinalizedAtAscIdAsc(
      UUID ownerId, ExpenseLedgerState state);

  long countByOwnerIdAndStateAndDeletedAtIsNull(UUID ownerId, ExpenseLedgerState state);
}
