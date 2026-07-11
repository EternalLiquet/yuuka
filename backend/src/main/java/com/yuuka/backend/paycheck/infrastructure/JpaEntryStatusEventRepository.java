package com.yuuka.backend.paycheck.infrastructure;

import com.yuuka.backend.paycheck.domain.EntryStatusEvent;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaEntryStatusEventRepository extends JpaRepository<EntryStatusEvent, UUID> {
  Page<EntryStatusEvent> findAllByEntryIdAndOwnerId(UUID entryId, UUID ownerId, Pageable pageable);
}
