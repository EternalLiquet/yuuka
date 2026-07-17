package com.yuuka.backend.recurring.infrastructure;

import com.yuuka.backend.recurring.domain.RecurringBillDefinition;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaRecurringBillDefinitionRepository
    extends JpaRepository<RecurringBillDefinition, UUID> {
  Optional<RecurringBillDefinition> findByIdAndOwnerIdAndDeletedAtIsNull(UUID id, UUID ownerId);

  List<RecurringBillDefinition> findAllByOwnerIdAndDeletedAtIsNullOrderByDueDayAscNameAscIdAsc(
      UUID ownerId);

  List<RecurringBillDefinition>
      findAllByOwnerIdAndActiveTrueAndDeletedAtIsNullOrderByDueDayAscNameAscIdAsc(UUID ownerId);
}
