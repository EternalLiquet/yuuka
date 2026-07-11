package com.yuuka.backend.template.infrastructure;

import com.yuuka.backend.template.domain.TemplateEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaTemplateEntryRepository extends JpaRepository<TemplateEntry, UUID> {
  Optional<TemplateEntry> findByIdAndOwnerId(UUID id, UUID ownerId);

  List<TemplateEntry> findAllByTemplateIdAndOwnerIdOrderByPosition(UUID templateId, UUID ownerId);

  @Query(
      "select coalesce(max(entry.position), -1) from TemplateEntry entry "
          + "where entry.templateId = :templateId")
  int findMaxPosition(@Param("templateId") UUID templateId);

  long countByTemplateIdAndOwnerId(UUID templateId, UUID ownerId);

  void deleteAllByTemplateIdAndOwnerId(UUID templateId, UUID ownerId);
}
