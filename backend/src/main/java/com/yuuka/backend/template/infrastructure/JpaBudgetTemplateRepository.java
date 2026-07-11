package com.yuuka.backend.template.infrastructure;

import com.yuuka.backend.template.domain.BudgetTemplate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaBudgetTemplateRepository extends JpaRepository<BudgetTemplate, UUID> {
  Optional<BudgetTemplate> findByIdAndOwnerId(UUID id, UUID ownerId);

  List<BudgetTemplate> findAllByOwnerIdOrderByArchivedAscUpdatedAtDesc(UUID ownerId);

  boolean existsByOwnerId(UUID ownerId);
}
