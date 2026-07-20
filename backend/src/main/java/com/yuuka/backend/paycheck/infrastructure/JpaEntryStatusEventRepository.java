package com.yuuka.backend.paycheck.infrastructure;

import com.yuuka.backend.paycheck.domain.EntryStatusEvent;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaEntryStatusEventRepository extends JpaRepository<EntryStatusEvent, UUID> {
  Page<EntryStatusEvent> findAllByEntryIdAndOwnerId(UUID entryId, UUID ownerId, Pageable pageable);

  @Query(
      value =
          """
          select distinct on (event.entry_id) event.*
          from entry_status_events event
          where event.owner_id = :ownerId
            and event.entry_id in (:entryIds)
          order by event.entry_id, event.recorded_at desc, event.id desc
          """,
      nativeQuery = true)
  List<EntryStatusEvent> findLatestByOwnerIdAndEntryIds(
      @Param("ownerId") UUID ownerId, @Param("entryIds") Collection<UUID> entryIds);
}
