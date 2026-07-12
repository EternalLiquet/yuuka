package com.yuuka.backend.payback.infrastructure;

import com.yuuka.backend.payback.domain.Payback;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaPaybackRepository extends JpaRepository<Payback, UUID> {
  Optional<Payback> findByIdAndOwnerIdAndDeletedAtIsNull(UUID id, UUID ownerId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select payback from Payback payback "
          + "where payback.id = :id and payback.ownerId = :ownerId and payback.deletedAt is null")
  Optional<Payback> findByIdAndOwnerIdForUpdate(
      @Param("id") UUID id, @Param("ownerId") UUID ownerId);

  List<Payback> findAllByOwnerIdAndDeletedAtIsNull(UUID ownerId);
}
