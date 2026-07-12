package com.yuuka.backend.payback.infrastructure;

import com.yuuka.backend.payback.domain.PaybackRepayment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaPaybackRepaymentRepository extends JpaRepository<PaybackRepayment, UUID> {
  @Query(
      "select coalesce(sum(repayment.amountMinor), 0) from PaybackRepayment repayment "
          + "where repayment.paybackId = :paybackId and repayment.ownerId = :ownerId "
          + "and repayment.reversedAt is null")
  long sumActiveAmountByPaybackIdAndOwnerId(
      @Param("paybackId") UUID paybackId, @Param("ownerId") UUID ownerId);

  Optional<PaybackRepayment> findByEntryIdAndOwnerIdAndReversedAtIsNull(UUID entryId, UUID ownerId);

  List<PaybackRepayment> findAllByPaybackIdAndOwnerIdOrderByAppliedAtDesc(
      UUID paybackId, UUID ownerId);

  long countByPaybackIdAndOwnerId(UUID paybackId, UUID ownerId);
}
