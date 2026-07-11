package com.yuuka.backend.paycheck.infrastructure;

import com.yuuka.backend.paycheck.domain.PaycheckEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaPaycheckEntryRepository extends JpaRepository<PaycheckEntry, UUID> {
  Optional<PaycheckEntry> findByIdAndOwnerIdAndDeletedAtIsNull(UUID id, UUID ownerId);

  List<PaycheckEntry> findAllByPaycheckIdAndOwnerIdAndDeletedAtIsNullOrderByPosition(
      UUID paycheckId, UUID ownerId);

  @Query(
      "select coalesce(max(entry.position), -1) from PaycheckEntry entry "
          + "where entry.paycheckId = :paycheckId and entry.deletedAt is null")
  int findMaxLivePosition(@Param("paycheckId") UUID paycheckId);

  long countByPaycheckIdAndOwnerIdAndDeletedAtIsNull(UUID paycheckId, UUID ownerId);
}
