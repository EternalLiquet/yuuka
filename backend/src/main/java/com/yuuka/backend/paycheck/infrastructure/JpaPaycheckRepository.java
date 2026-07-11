package com.yuuka.backend.paycheck.infrastructure;

import com.yuuka.backend.paycheck.domain.Paycheck;
import com.yuuka.backend.paycheck.domain.PaycheckState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface JpaPaycheckRepository
    extends JpaRepository<Paycheck, UUID>, JpaSpecificationExecutor<Paycheck> {
  Optional<Paycheck> findByIdAndOwnerId(UUID id, UUID ownerId);

  List<Paycheck> findAllByOwnerId(UUID ownerId);

  List<Paycheck> findAllByOwnerIdAndStateOrderByIncomeDateDescUpdatedAtDesc(
      UUID ownerId, PaycheckState state);

  boolean existsByOwnerId(UUID ownerId);
}
