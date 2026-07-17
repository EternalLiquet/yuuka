package com.yuuka.backend.paycheck.infrastructure;

import com.yuuka.backend.paycheck.domain.Paycheck;
import com.yuuka.backend.paycheck.domain.PaycheckState;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaPaycheckRepository
    extends JpaRepository<Paycheck, UUID>, JpaSpecificationExecutor<Paycheck> {
  Optional<Paycheck> findByIdAndOwnerId(UUID id, UUID ownerId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Paycheck p where p.id = :id and p.ownerId = :ownerId")
  Optional<Paycheck> findByIdAndOwnerIdForUpdate(
      @Param("id") UUID id, @Param("ownerId") UUID ownerId);

  List<Paycheck> findAllByOwnerId(UUID ownerId);

  List<Paycheck> findAllByIdInAndOwnerId(java.util.Collection<UUID> ids, UUID ownerId);

  List<Paycheck> findAllByOwnerIdAndStateOrderByIncomeDateDescUpdatedAtDesc(
      UUID ownerId, PaycheckState state);

  boolean existsByOwnerId(UUID ownerId);
}
