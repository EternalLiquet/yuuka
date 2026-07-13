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
      "select coalesce(max(entry.position), -1) from PaycheckEntry entry "
          + "where entry.paycheckId = :paycheckId and entry.deletedAt is null")
  int findMaxLivePosition(@Param("paycheckId") UUID paycheckId);

  long countByPaycheckIdAndOwnerIdAndDeletedAtIsNull(UUID paycheckId, UUID ownerId);

  long countByPaybackIdAndOwnerIdAndDeletedAtIsNull(UUID paybackId, UUID ownerId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select entry from PaycheckEntry entry "
          + "where entry.paybackId = :paybackId and entry.ownerId = :ownerId "
          + "and entry.deletedAt is null "
          + "order by entry.paycheckId, entry.position, entry.id")
  List<PaycheckEntry> findLiveAssignedToPaybackForUpdate(
      @Param("paybackId") UUID paybackId, @Param("ownerId") UUID ownerId);
}
