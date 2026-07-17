package com.yuuka.backend.bucket.domain;

import com.yuuka.backend.common.api.MoneyArithmetic;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class BucketCalculator {
  public BucketMetrics calculate(long budgetMinor, List<Long> transactionAmountsMinor) {
    if (budgetMinor < 0) {
      throw new IllegalArgumentException("Bucket budget must not be negative");
    }
    long spent = 0;
    for (long amount : transactionAmountsMinor) {
      spent = MoneyArithmetic.add(spent, amount);
    }
    return new BucketMetrics(budgetMinor, spent, MoneyArithmetic.subtract(budgetMinor, spent));
  }
}
