package com.yuuka.backend.expense.infrastructure;

import com.yuuka.backend.expense.domain.ExpenseLedgerSettlement;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaExpenseLedgerSettlementRepository
    extends JpaRepository<ExpenseLedgerSettlement, UUID> {
  Optional<ExpenseLedgerSettlement> findByLedgerIdAndOwnerId(UUID ledgerId, UUID ownerId);

  List<ExpenseLedgerSettlement> findAllByLedgerIdInAndOwnerId(
      Collection<UUID> ledgerIds, UUID ownerId);

  boolean existsByLedgerIdAndOwnerId(UUID ledgerId, UUID ownerId);
}
