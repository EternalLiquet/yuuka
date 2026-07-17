package com.yuuka.backend.recurring.infrastructure;

import com.yuuka.backend.recurring.domain.RecurringBillDefinition;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaRecurringBillDefinitionRepository
    extends JpaRepository<RecurringBillDefinition, UUID> {
  Optional<RecurringBillDefinition> findByIdAndOwnerIdAndDeletedAtIsNull(UUID id, UUID ownerId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select definition from RecurringBillDefinition definition "
          + "where definition.id = :id and definition.ownerId = :ownerId "
          + "and definition.deletedAt is null")
  Optional<RecurringBillDefinition> findByIdAndOwnerIdForUpdate(
      @Param("id") UUID id, @Param("ownerId") UUID ownerId);

  List<RecurringBillDefinition> findAllByOwnerIdAndDeletedAtIsNullOrderByDueDayAscNameAscIdAsc(
      UUID ownerId);

  List<RecurringBillDefinition>
      findAllByOwnerIdAndActiveTrueAndDeletedAtIsNullOrderByDueDayAscNameAscIdAsc(UUID ownerId);
}
