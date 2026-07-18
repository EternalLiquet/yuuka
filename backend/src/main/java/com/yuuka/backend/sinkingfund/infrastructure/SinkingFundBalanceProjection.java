package com.yuuka.backend.sinkingfund.infrastructure;

import java.util.UUID;

public interface SinkingFundBalanceProjection {
  UUID getSinkingFundId();

  Long getCurrentBalanceMinor();

  Long getTransactionCount();
}
