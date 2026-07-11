package com.yuuka.backend.audit.infrastructure;

import com.yuuka.backend.audit.domain.AuditEvent;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaAuditEventRepository extends JpaRepository<AuditEvent, UUID> {
  Page<AuditEvent> findAllByOwnerIdAndEntityTypeAndEntityId(
      UUID ownerId, String entityType, UUID entityId, Pageable pageable);
}
