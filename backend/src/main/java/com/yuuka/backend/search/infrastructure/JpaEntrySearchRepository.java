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
                 entry.paymentMethod as paymentMethod,
                 entry.status as status,
                 paycheck.name as paycheckName,
                 paycheck.incomeDate as paycheckIncomeDate,
                 paycheck.state as paycheckState,
                 case
                   when paycheck.amountMinor <> (
                     select coalesce(sum(metricEntry.amountMinor), 0)
                     from PaycheckEntry metricEntry
                     where metricEntry.ownerId = paycheck.ownerId
                       and metricEntry.paycheckId = paycheck.id
                       and metricEntry.deletedAt is null
                   )
                   or (
                     select count(metricEntry)
                     from PaycheckEntry metricEntry
                     where metricEntry.ownerId = paycheck.ownerId
                       and metricEntry.paycheckId = paycheck.id
                       and metricEntry.deletedAt is null
                   ) = 0
                   or exists (
                     select 1
                     from PaycheckEntry metricEntry
                     where metricEntry.ownerId = paycheck.ownerId
                       and metricEntry.paycheckId = paycheck.id
                       and metricEntry.deletedAt is null
                       and metricEntry.status <> com.yuuka.backend.paycheck.domain.EntryStatus.POSTED
                   )
                   then true
                   else false
                 end as requiresAttention,
                 paycheck.reopenedAt as reopenedAt
          from PaycheckEntry entry
          join Paycheck paycheck
            on paycheck.id = entry.paycheckId and paycheck.ownerId = entry.ownerId
          where entry.ownerId = :ownerId
            and entry.deletedAt is null
            and (
              :activeOnly = false
              or (
                paycheck.state = com.yuuka.backend.paycheck.domain.PaycheckState.ACTIVE
                and (
                  paycheck.reopenedAt is not null
                  or paycheck.amountMinor <> (
                    select coalesce(sum(metricEntry.amountMinor), 0)
                    from PaycheckEntry metricEntry
                    where metricEntry.ownerId = paycheck.ownerId
                      and metricEntry.paycheckId = paycheck.id
                      and metricEntry.deletedAt is null
                  )
                  or (
                    select count(metricEntry)
                    from PaycheckEntry metricEntry
                    where metricEntry.ownerId = paycheck.ownerId
                      and metricEntry.paycheckId = paycheck.id
                      and metricEntry.deletedAt is null
                  ) = 0
                  or exists (
                    select 1
                    from PaycheckEntry metricEntry
                    where metricEntry.ownerId = paycheck.ownerId
                      and metricEntry.paycheckId = paycheck.id
                      and metricEntry.deletedAt is null
                      and metricEntry.status <> com.yuuka.backend.paycheck.domain.EntryStatus.POSTED
                  )
                )
              )
            )
            and (
              :historyOnly = false
              or paycheck.state <> com.yuuka.backend.paycheck.domain.PaycheckState.ACTIVE
              or (
                paycheck.reopenedAt is null
                and paycheck.amountMinor = (
                  select coalesce(sum(metricEntry.amountMinor), 0)
                  from PaycheckEntry metricEntry
                  where metricEntry.ownerId = paycheck.ownerId
                    and metricEntry.paycheckId = paycheck.id
                    and metricEntry.deletedAt is null
                )
                and (
                  select count(metricEntry)
                  from PaycheckEntry metricEntry
                  where metricEntry.ownerId = paycheck.ownerId
                    and metricEntry.paycheckId = paycheck.id
                    and metricEntry.deletedAt is null
                ) > 0
                and not exists (
                  select 1
                  from PaycheckEntry metricEntry
                  where metricEntry.ownerId = paycheck.ownerId
                    and metricEntry.paycheckId = paycheck.id
                    and metricEntry.deletedAt is null
                    and metricEntry.status <> com.yuuka.backend.paycheck.domain.EntryStatus.POSTED
                )
              )
            )
            and (
              (:query is not null and (
                lower(entry.name) like concat('%', :likeQuery, '%') escape '!'
                or lower(paycheck.name) like concat('%', :likeQuery, '%') escape '!'
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
            and (
              :activeOnly = false
              or (
                paycheck.state = com.yuuka.backend.paycheck.domain.PaycheckState.ACTIVE
                and (
                  paycheck.reopenedAt is not null
                  or paycheck.amountMinor <> (
                    select coalesce(sum(metricEntry.amountMinor), 0)
                    from PaycheckEntry metricEntry
                    where metricEntry.ownerId = paycheck.ownerId
                      and metricEntry.paycheckId = paycheck.id
                      and metricEntry.deletedAt is null
                  )
                  or (
                    select count(metricEntry)
                    from PaycheckEntry metricEntry
                    where metricEntry.ownerId = paycheck.ownerId
                      and metricEntry.paycheckId = paycheck.id
                      and metricEntry.deletedAt is null
                  ) = 0
                  or exists (
                    select 1
                    from PaycheckEntry metricEntry
                    where metricEntry.ownerId = paycheck.ownerId
                      and metricEntry.paycheckId = paycheck.id
                      and metricEntry.deletedAt is null
                      and metricEntry.status <> com.yuuka.backend.paycheck.domain.EntryStatus.POSTED
                  )
                )
              )
            )
            and (
              :historyOnly = false
              or paycheck.state <> com.yuuka.backend.paycheck.domain.PaycheckState.ACTIVE
              or (
                paycheck.reopenedAt is null
                and paycheck.amountMinor = (
                  select coalesce(sum(metricEntry.amountMinor), 0)
                  from PaycheckEntry metricEntry
                  where metricEntry.ownerId = paycheck.ownerId
                    and metricEntry.paycheckId = paycheck.id
                    and metricEntry.deletedAt is null
                )
                and (
                  select count(metricEntry)
                  from PaycheckEntry metricEntry
                  where metricEntry.ownerId = paycheck.ownerId
                    and metricEntry.paycheckId = paycheck.id
                    and metricEntry.deletedAt is null
                ) > 0
                and not exists (
                  select 1
                  from PaycheckEntry metricEntry
                  where metricEntry.ownerId = paycheck.ownerId
                    and metricEntry.paycheckId = paycheck.id
                    and metricEntry.deletedAt is null
                    and metricEntry.status <> com.yuuka.backend.paycheck.domain.EntryStatus.POSTED
                )
              )
            )
            and (
              (:query is not null and (
                lower(entry.name) like concat('%', :likeQuery, '%') escape '!'
                or lower(paycheck.name) like concat('%', :likeQuery, '%') escape '!'
              ))
              or (:amountMinor is not null and entry.amountMinor = :amountMinor)
            )
          """)
  Page<EntrySearchProjection> searchEntries(
      @Param("ownerId") UUID ownerId,
      @Param("query") String query,
      @Param("likeQuery") String likeQuery,
      @Param("amountMinor") Long amountMinor,
      @Param("activeOnly") boolean activeOnly,
      @Param("historyOnly") boolean historyOnly,
      Pageable pageable);
}
