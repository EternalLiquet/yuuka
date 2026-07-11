package com.yuuka.backend.bucket.infrastructure;

import com.yuuka.backend.bucket.domain.BucketTransaction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaBucketTransactionRepository extends JpaRepository<BucketTransaction, UUID> {
  Optional<BucketTransaction> findByIdAndOwnerIdAndDeletedAtIsNull(UUID id, UUID ownerId);

  List<BucketTransaction>
      findAllByEntryIdAndOwnerIdAndDeletedAtIsNullOrderByEffectiveDateDescCreatedAtDesc(
          UUID entryId, UUID ownerId);
}
