package com.yuuka.backend.sinkingfund.infrastructure;

import java.math.BigDecimal;
import java.util.UUID;

public interface SinkingFundBalanceProjection {
  UUID getSinkingFundId();

  BigDecimal getCurrentBalanceMinor();

  Long getTransactionCount();
}
