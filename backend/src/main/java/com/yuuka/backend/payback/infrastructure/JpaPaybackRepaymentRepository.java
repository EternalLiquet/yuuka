package com.yuuka.backend.payback.infrastructure;

import com.yuuka.backend.payback.domain.PaybackRepayment;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaPaybackRepaymentRepository extends JpaRepository<PaybackRepayment, UUID> {
  @Query(
      "select coalesce(sum(repayment.amountMinor), 0) from PaybackRepayment repayment "
          + "where repayment.paybackId = :paybackId and repayment.ownerId = :ownerId "
          + "and repayment.reversedAt is null")
  long sumActiveAmountByPaybackIdAndOwnerId(
      @Param("paybackId") UUID paybackId, @Param("ownerId") UUID ownerId);

  @Query(
      value =
          """
          select
              repayment.payback_id as paybackId,
              coalesce(sum(case when repayment.reversed_at is null then repayment.amount_minor else 0 end), 0) as activeRepaidMinor,
              count(*) as repaymentCount
          from payback_repayments repayment
          where repayment.owner_id = :ownerId
            and repayment.payback_id in (:paybackIds)
          group by repayment.payback_id
          """,
      nativeQuery = true)
  List<PaybackRepaymentAggregateProjection> aggregateByPaybackIds(
      @Param("ownerId") UUID ownerId, @Param("paybackIds") java.util.Collection<UUID> paybackIds);

  Optional<PaybackRepayment> findByEntryIdAndOwnerIdAndReversedAtIsNull(UUID entryId, UUID ownerId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select repayment from PaybackRepayment repayment "
          + "where repayment.paybackId = :paybackId and repayment.ownerId = :ownerId "
          + "and repayment.reversedAt is null "
          + "order by repayment.entryId, repayment.id")
  List<PaybackRepayment> findActiveByPaybackIdAndOwnerIdForUpdate(
      @Param("paybackId") UUID paybackId, @Param("ownerId") UUID ownerId);

  List<PaybackRepayment> findAllByPaybackIdAndOwnerIdOrderByAppliedAtDesc(
      UUID paybackId, UUID ownerId);

  Page<PaybackRepayment> findAllByPaybackIdAndOwnerIdOrderByAppliedAtDescIdDesc(
      UUID paybackId, UUID ownerId, Pageable pageable);

  long countByPaybackIdAndOwnerId(UUID paybackId, UUID ownerId);
}
