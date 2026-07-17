package com.yuuka.backend.payback.infrastructure;

import java.util.UUID;

public interface PaybackRepaymentAggregateProjection {
  UUID getPaybackId();

  Long getActiveRepaidMinor();

  Long getRepaymentCount();
}
