package com.yuuka.backend.search.infrastructure;

import com.yuuka.backend.paycheck.domain.PaycheckEntry;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaEntrySearchRepository extends JpaRepository<PaycheckEntry, UUID> {
  @Query(
      value =
          """
          select entry.id as entryId,
                 paycheck.id as paycheckId,
                 entry.name as entryName,
                 entry.amountMinor as amountMinor,
                 entry.entryType as entryType,
                 entry.status as status,
                 paycheck.name as paycheckName,
                 paycheck.incomeDate as paycheckIncomeDate,
                 paycheck.state as paycheckState
          from PaycheckEntry entry
          join Paycheck paycheck
            on paycheck.id = entry.paycheckId and paycheck.ownerId = entry.ownerId
          where entry.ownerId = :ownerId
            and entry.deletedAt is null
            and (:activeOnly = false or paycheck.state = com.yuuka.backend.paycheck.domain.PaycheckState.ACTIVE)
            and (:historyOnly = false or paycheck.state <> com.yuuka.backend.paycheck.domain.PaycheckState.ACTIVE)
            and (
              (:query is not null and (
                lower(entry.name) like concat('%', :query, '%')
                or lower(paycheck.name) like concat('%', :query, '%')
              ))
              or (:amountMinor is not null and entry.amountMinor = :amountMinor)
            )
          order by
            case
              when :query is not null and lower(entry.name) = :query then 0
              when :query is not null and lower(paycheck.name) = :query then 1
              else 2
            end,
            paycheck.incomeDate desc,
            paycheck.id asc,
            entry.position asc,
            entry.id asc
          """,
      countQuery =
          """
          select count(entry)
          from PaycheckEntry entry
          join Paycheck paycheck
            on paycheck.id = entry.paycheckId and paycheck.ownerId = entry.ownerId
          where entry.ownerId = :ownerId
            and entry.deletedAt is null
            and (:activeOnly = false or paycheck.state = com.yuuka.backend.paycheck.domain.PaycheckState.ACTIVE)
            and (:historyOnly = false or paycheck.state <> com.yuuka.backend.paycheck.domain.PaycheckState.ACTIVE)
            and (
              (:query is not null and (
                lower(entry.name) like concat('%', :query, '%')
                or lower(paycheck.name) like concat('%', :query, '%')
              ))
              or (:amountMinor is not null and entry.amountMinor = :amountMinor)
            )
          """)
  Page<EntrySearchProjection> searchEntries(
      @Param("ownerId") UUID ownerId,
      @Param("query") String query,
      @Param("amountMinor") Long amountMinor,
      @Param("activeOnly") boolean activeOnly,
      @Param("historyOnly") boolean historyOnly,
      Pageable pageable);
}
