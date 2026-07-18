package com.yuuka.backend.sinkingfund.infrastructure;

import com.yuuka.backend.sinkingfund.domain.SinkingFund;
import com.yuuka.backend.sinkingfund.domain.SinkingFundState;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaSinkingFundRepository extends JpaRepository<SinkingFund, UUID> {
  Optional<SinkingFund> findByIdAndOwnerId(UUID id, UUID ownerId);

  Optional<SinkingFund> findByIdAndOwnerIdAndState(UUID id, UUID ownerId, SinkingFundState state);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select fund from SinkingFund fund " + "where fund.id = :id and fund.ownerId = :ownerId")
  Optional<SinkingFund> findByIdAndOwnerIdForUpdate(
      @Param("id") UUID id, @Param("ownerId") UUID ownerId);

  List<SinkingFund> findAllByOwnerIdOrderByStateAscPositionAscCreatedAtAscIdAsc(UUID ownerId);

  List<SinkingFund> findAllByOwnerIdAndStateOrderByPositionAscCreatedAtAscIdAsc(
      UUID ownerId, SinkingFundState state);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select fund from SinkingFund fund "
          + "where fund.ownerId = :ownerId and fund.state = 'ACTIVE' "
          + "order by fund.position, fund.createdAt, fund.id")
  List<SinkingFund> findActiveByOwnerIdForUpdate(@Param("ownerId") UUID ownerId);

  @Query(
      "select coalesce(max(fund.position), -1) from SinkingFund fund "
          + "where fund.ownerId = :ownerId and fund.state = 'ACTIVE'")
  int findMaxActivePosition(@Param("ownerId") UUID ownerId);
}
