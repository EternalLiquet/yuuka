package com.yuuka.backend.recurring.infrastructure;

import com.yuuka.backend.recurring.domain.RecurringBillOccurrenceAmount;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaRecurringBillOccurrenceAmountRepository
    extends JpaRepository<RecurringBillOccurrenceAmount, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select amount from RecurringBillOccurrenceAmount amount "
          + "where amount.ownerId = :ownerId and amount.definitionId = :definitionId "
          + "and amount.occurrenceDate = :occurrenceDate")
  Optional<RecurringBillOccurrenceAmount> findForUpdate(
      @Param("ownerId") UUID ownerId,
      @Param("definitionId") UUID definitionId,
      @Param("occurrenceDate") LocalDate occurrenceDate);

  List<RecurringBillOccurrenceAmount> findAllByOwnerIdAndDefinitionIdInAndOccurrenceDateBetween(
      UUID ownerId, Set<UUID> definitionIds, LocalDate from, LocalDate through);
}
