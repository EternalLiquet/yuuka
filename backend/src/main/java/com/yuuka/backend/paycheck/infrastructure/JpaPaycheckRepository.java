package com.yuuka.backend.paycheck.infrastructure;

import com.yuuka.backend.paycheck.domain.Paycheck;
import com.yuuka.backend.paycheck.domain.PaycheckState;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

  @Query(
      value =
          """
          select p.*
          from paychecks p
          where p.owner_id = :ownerId
            and p.state = 'ACTIVE'
            and (
              p.reopened_at is not null
              or p.amount_minor <> (
                select coalesce(sum(e.amount_minor), 0)
                from paycheck_entries e
                where e.paycheck_id = p.id
                  and e.owner_id = p.owner_id
                  and e.deleted_at is null
              )
              or not (
                (
                  select count(e.id)
                  from paycheck_entries e
                  where e.paycheck_id = p.id
                    and e.owner_id = p.owner_id
                    and e.deleted_at is null
                ) > 0
                and (
                  select count(e.id)
                  from paycheck_entries e
                  where e.paycheck_id = p.id
                    and e.owner_id = p.owner_id
                    and e.deleted_at is null
                    and e.status = 'NOT_PAID'
                ) = 0
                and (
                  select count(e.id)
                  from paycheck_entries e
                  where e.paycheck_id = p.id
                    and e.owner_id = p.owner_id
                    and e.deleted_at is null
                    and e.status = 'PROCESSING'
                ) = 0
                and (
                  select coalesce(sum(case when e.status = 'POSTED' then e.amount_minor else 0 end), 0)
                  from paycheck_entries e
                  where e.paycheck_id = p.id
                    and e.owner_id = p.owner_id
                    and e.deleted_at is null
                ) = (
                  select coalesce(sum(e.amount_minor), 0)
                  from paycheck_entries e
                  where e.paycheck_id = p.id
                    and e.owner_id = p.owner_id
                    and e.deleted_at is null
                )
              )
            )
          order by p.income_date desc, p.updated_at desc, p.id desc
          """,
      countQuery =
          """
          select count(*)
          from paychecks p
          where p.owner_id = :ownerId
            and p.state = 'ACTIVE'
            and (
              p.reopened_at is not null
              or p.amount_minor <> (
                select coalesce(sum(e.amount_minor), 0)
                from paycheck_entries e
                where e.paycheck_id = p.id
                  and e.owner_id = p.owner_id
                  and e.deleted_at is null
              )
              or not (
                (
                  select count(e.id)
                  from paycheck_entries e
                  where e.paycheck_id = p.id
                    and e.owner_id = p.owner_id
                    and e.deleted_at is null
                ) > 0
                and (
                  select count(e.id)
                  from paycheck_entries e
                  where e.paycheck_id = p.id
                    and e.owner_id = p.owner_id
                    and e.deleted_at is null
                    and e.status = 'NOT_PAID'
                ) = 0
                and (
                  select count(e.id)
                  from paycheck_entries e
                  where e.paycheck_id = p.id
                    and e.owner_id = p.owner_id
                    and e.deleted_at is null
                    and e.status = 'PROCESSING'
                ) = 0
                and (
                  select coalesce(sum(case when e.status = 'POSTED' then e.amount_minor else 0 end), 0)
                  from paycheck_entries e
                  where e.paycheck_id = p.id
                    and e.owner_id = p.owner_id
                    and e.deleted_at is null
                ) = (
                  select coalesce(sum(e.amount_minor), 0)
                  from paycheck_entries e
                  where e.paycheck_id = p.id
                    and e.owner_id = p.owner_id
                    and e.deleted_at is null
                )
              )
            )
          """,
      nativeQuery = true)
  Page<Paycheck> findActivePage(@Param("ownerId") UUID ownerId, Pageable pageable);

  @Query(
      value =
          """
          select p.*
          from paychecks p
          where p.owner_id = :ownerId
            and (:term = ''
              or position(:term in lower(p.name)) > 0
              or position(:term in lower(coalesce(p.source, ''))) > 0)
            and (cast(:fromDate as date) is null or p.income_date >= cast(:fromDate as date))
            and (cast(:toDate as date) is null or p.income_date <= cast(:toDate as date))
            and (
              p.state <> 'ACTIVE'
              or (
                p.reopened_at is null
                and not (
                  p.amount_minor <> (
                    select coalesce(sum(e.amount_minor), 0)
                    from paycheck_entries e
                    where e.paycheck_id = p.id
                      and e.owner_id = p.owner_id
                      and e.deleted_at is null
                  )
                  or not (
                    (
                      select count(e.id)
                      from paycheck_entries e
                      where e.paycheck_id = p.id
                        and e.owner_id = p.owner_id
                        and e.deleted_at is null
                    ) > 0
                    and (
                      select count(e.id)
                      from paycheck_entries e
                      where e.paycheck_id = p.id
                        and e.owner_id = p.owner_id
                        and e.deleted_at is null
                        and e.status = 'NOT_PAID'
                    ) = 0
                    and (
                      select count(e.id)
                      from paycheck_entries e
                      where e.paycheck_id = p.id
                        and e.owner_id = p.owner_id
                        and e.deleted_at is null
                        and e.status = 'PROCESSING'
                    ) = 0
                    and (
                      select coalesce(sum(case when e.status = 'POSTED' then e.amount_minor else 0 end), 0)
                      from paycheck_entries e
                      where e.paycheck_id = p.id
                        and e.owner_id = p.owner_id
                        and e.deleted_at is null
                    ) = (
                      select coalesce(sum(e.amount_minor), 0)
                      from paycheck_entries e
                      where e.paycheck_id = p.id
                        and e.owner_id = p.owner_id
                        and e.deleted_at is null
                    )
                  )
                )
              )
            )
          order by p.income_date desc, p.updated_at desc, p.id desc
          """,
      countQuery =
          """
          select count(*)
          from paychecks p
          where p.owner_id = :ownerId
            and (:term = ''
              or position(:term in lower(p.name)) > 0
              or position(:term in lower(coalesce(p.source, ''))) > 0)
            and (cast(:fromDate as date) is null or p.income_date >= cast(:fromDate as date))
            and (cast(:toDate as date) is null or p.income_date <= cast(:toDate as date))
            and (
              p.state <> 'ACTIVE'
              or (
                p.reopened_at is null
                and not (
                  p.amount_minor <> (
                    select coalesce(sum(e.amount_minor), 0)
                    from paycheck_entries e
                    where e.paycheck_id = p.id
                      and e.owner_id = p.owner_id
                      and e.deleted_at is null
                  )
                  or not (
                    (
                      select count(e.id)
                      from paycheck_entries e
                      where e.paycheck_id = p.id
                        and e.owner_id = p.owner_id
                        and e.deleted_at is null
                    ) > 0
                    and (
                      select count(e.id)
                      from paycheck_entries e
                      where e.paycheck_id = p.id
                        and e.owner_id = p.owner_id
                        and e.deleted_at is null
                        and e.status = 'NOT_PAID'
                    ) = 0
                    and (
                      select count(e.id)
                      from paycheck_entries e
                      where e.paycheck_id = p.id
                        and e.owner_id = p.owner_id
                        and e.deleted_at is null
                        and e.status = 'PROCESSING'
                    ) = 0
                    and (
                      select coalesce(sum(case when e.status = 'POSTED' then e.amount_minor else 0 end), 0)
                      from paycheck_entries e
                      where e.paycheck_id = p.id
                        and e.owner_id = p.owner_id
                        and e.deleted_at is null
                    ) = (
                      select coalesce(sum(e.amount_minor), 0)
                      from paycheck_entries e
                      where e.paycheck_id = p.id
                        and e.owner_id = p.owner_id
                        and e.deleted_at is null
                    )
                  )
                )
              )
            )
          """,
      nativeQuery = true)
  Page<Paycheck> findHistoryPageNewest(
      @Param("ownerId") UUID ownerId,
      @Param("term") String term,
      @Param("fromDate") java.sql.Date fromDate,
      @Param("toDate") java.sql.Date toDate,
      Pageable pageable);

  @Query(
      value =
          """
          select p.*
          from paychecks p
          where p.owner_id = :ownerId
            and (:term = ''
              or position(:term in lower(p.name)) > 0
              or position(:term in lower(coalesce(p.source, ''))) > 0)
            and (cast(:fromDate as date) is null or p.income_date >= cast(:fromDate as date))
            and (cast(:toDate as date) is null or p.income_date <= cast(:toDate as date))
            and (
              p.state <> 'ACTIVE'
              or (
                p.reopened_at is null
                and not (
                  p.amount_minor <> (
                    select coalesce(sum(e.amount_minor), 0)
                    from paycheck_entries e
                    where e.paycheck_id = p.id
                      and e.owner_id = p.owner_id
                      and e.deleted_at is null
                  )
                  or not (
                    (
                      select count(e.id)
                      from paycheck_entries e
                      where e.paycheck_id = p.id
                        and e.owner_id = p.owner_id
                        and e.deleted_at is null
                    ) > 0
                    and (
                      select count(e.id)
                      from paycheck_entries e
                      where e.paycheck_id = p.id
                        and e.owner_id = p.owner_id
                        and e.deleted_at is null
                        and e.status = 'NOT_PAID'
                    ) = 0
                    and (
                      select count(e.id)
                      from paycheck_entries e
                      where e.paycheck_id = p.id
                        and e.owner_id = p.owner_id
                        and e.deleted_at is null
                        and e.status = 'PROCESSING'
                    ) = 0
                    and (
                      select coalesce(sum(case when e.status = 'POSTED' then e.amount_minor else 0 end), 0)
                      from paycheck_entries e
                      where e.paycheck_id = p.id
                        and e.owner_id = p.owner_id
                        and e.deleted_at is null
                    ) = (
                      select coalesce(sum(e.amount_minor), 0)
                      from paycheck_entries e
                      where e.paycheck_id = p.id
                        and e.owner_id = p.owner_id
                        and e.deleted_at is null
                    )
                  )
                )
              )
            )
          order by p.income_date asc, p.updated_at asc, p.id asc
          """,
      countQuery =
          """
          select count(*)
          from paychecks p
          where p.owner_id = :ownerId
            and (:term = ''
              or position(:term in lower(p.name)) > 0
              or position(:term in lower(coalesce(p.source, ''))) > 0)
            and (cast(:fromDate as date) is null or p.income_date >= cast(:fromDate as date))
            and (cast(:toDate as date) is null or p.income_date <= cast(:toDate as date))
            and (
              p.state <> 'ACTIVE'
              or (
                p.reopened_at is null
                and not (
                  p.amount_minor <> (
                    select coalesce(sum(e.amount_minor), 0)
                    from paycheck_entries e
                    where e.paycheck_id = p.id
                      and e.owner_id = p.owner_id
                      and e.deleted_at is null
                  )
                  or not (
                    (
                      select count(e.id)
                      from paycheck_entries e
                      where e.paycheck_id = p.id
                        and e.owner_id = p.owner_id
                        and e.deleted_at is null
                    ) > 0
                    and (
                      select count(e.id)
                      from paycheck_entries e
                      where e.paycheck_id = p.id
                        and e.owner_id = p.owner_id
                        and e.deleted_at is null
                        and e.status = 'NOT_PAID'
                    ) = 0
                    and (
                      select count(e.id)
                      from paycheck_entries e
                      where e.paycheck_id = p.id
                        and e.owner_id = p.owner_id
                        and e.deleted_at is null
                        and e.status = 'PROCESSING'
                    ) = 0
                    and (
                      select coalesce(sum(case when e.status = 'POSTED' then e.amount_minor else 0 end), 0)
                      from paycheck_entries e
                      where e.paycheck_id = p.id
                        and e.owner_id = p.owner_id
                        and e.deleted_at is null
                    ) = (
                      select coalesce(sum(e.amount_minor), 0)
                      from paycheck_entries e
                      where e.paycheck_id = p.id
                        and e.owner_id = p.owner_id
                        and e.deleted_at is null
                    )
                  )
                )
              )
            )
          """,
      nativeQuery = true)
  Page<Paycheck> findHistoryPageOldest(
      @Param("ownerId") UUID ownerId,
      @Param("term") String term,
      @Param("fromDate") java.sql.Date fromDate,
      @Param("toDate") java.sql.Date toDate,
      Pageable pageable);

  boolean existsByOwnerId(UUID ownerId);
}
