package com.yuuka.backend.paycheck.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yuuka.backend.common.api.BusinessRuleException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PaycheckCalculatorTests {
  private final PaycheckCalculator calculator = new PaycheckCalculator();

  @Test
  void preservesExactCentsAndSeparatesAllocationFromCompletion() {
    PaycheckMetrics metrics =
        calculator.calculate(
            193923,
            List.of(
                new AllocationLine(13052, EntryStatus.PROCESSING, false),
                new AllocationLine(180868, EntryStatus.POSTED, false)));

    assertThat(metrics.allocatedMinor()).isEqualTo(193920);
    assertThat(metrics.unallocatedMinor()).isEqualTo(3);
    assertThat(metrics.postedMinor()).isEqualTo(180868);
    assertThat(metrics.processingMinor()).isEqualTo(13052);
    assertThat(metrics.notPaidMinor()).isZero();
    assertThat(metrics.fullyAllocated()).isFalse();
    assertThat(metrics.fullyPosted()).isFalse();
  }

  @Test
  void ignoresDeletedEntriesAndReturnsZeroCompletionForNoAllocation() {
    PaycheckMetrics metrics =
        calculator.calculate(10000, List.of(new AllocationLine(10000, EntryStatus.POSTED, true)));

    assertThat(metrics.allocatedMinor()).isZero();
    assertThat(metrics.unallocatedMinor()).isEqualTo(10000);
    assertThat(metrics.allocationPercent()).isEqualByComparingTo("0.00");
    assertThat(metrics.completionPercent()).isEqualByComparingTo("0.00");
  }

  @Test
  void keepsFullyAllocatedPaycheckActiveWhileAnyEntryIsNotPosted() {
    PaycheckMetrics metrics =
        calculator.calculate(
            15000, List.of(new AllocationLine(15000, EntryStatus.PROCESSING, false)));

    assertThat(metrics.fullyAllocated()).isTrue();
    assertThat(metrics.requiresAttention()).isTrue();
  }

  @Test
  void marksFullyAllocatedAndPostedPaycheckComplete() {
    PaycheckMetrics metrics =
        calculator.calculate(15000, List.of(new AllocationLine(15000, EntryStatus.POSTED, false)));

    assertThat(metrics.fullyAllocated()).isTrue();
    assertThat(metrics.fullyPosted()).isTrue();
    assertThat(metrics.requiresAttention()).isFalse();
    assertThat(metrics.allocationPercent()).isEqualByComparingTo("100.00");
    assertThat(metrics.completionPercent()).isEqualByComparingTo("100.00");
  }

  @Test
  void treatsZeroAmountPaycheckWithNoEntriesAsStillNeedingAttention() {
    PaycheckMetrics metrics = calculator.calculate(0, List.of());

    assertThat(metrics.allocatedMinor()).isZero();
    assertThat(metrics.unallocatedMinor()).isZero();
    assertThat(metrics.fullyAllocated()).isTrue();
    assertThat(metrics.fullyPosted()).isFalse();
    assertThat(metrics.requiresAttention()).isTrue();
  }

  @Test
  void rejectsNegativePaycheckAmounts() {
    assertThatThrownBy(() -> calculator.calculate(-1, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Paycheck amount must not be negative");
  }

  @Test
  void rejectsAllocationTotalsThatExceedInt64MoneyRange() {
    assertThatThrownBy(
            () ->
                calculator.calculate(
                    Long.MAX_VALUE,
                    List.of(
                        new AllocationLine(Long.MAX_VALUE, EntryStatus.NOT_PAID, false),
                        new AllocationLine(1, EntryStatus.NOT_PAID, false))))
        .isInstanceOfSatisfying(
            BusinessRuleException.class,
            exception -> assertThat(exception.code()).isEqualTo("MONEY_AMOUNT_OVERFLOW"));
  }

  @Test
  void calculatesMetricsFromRepositoryTotals() {
    PaycheckMetrics metrics =
        calculator.calculateFromTotals(10000, 6000, 2000, 3000, 1000, 1, 2, 3);

    assertThat(metrics.allocatedMinor()).isEqualTo(6000);
    assertThat(metrics.unallocatedMinor()).isEqualTo(4000);
    assertThat(metrics.postedMinor()).isEqualTo(2000);
    assertThat(metrics.processingMinor()).isEqualTo(3000);
    assertThat(metrics.notPaidMinor()).isEqualTo(1000);
    assertThat(metrics.postedCount()).isEqualTo(1);
    assertThat(metrics.processingCount()).isEqualTo(2);
    assertThat(metrics.notPaidCount()).isEqualTo(3);
    assertThat(metrics.allocationPercent()).isEqualByComparingTo("60.00");
    assertThat(metrics.completionPercent()).isEqualByComparingTo("33.33");
  }

  @Test
  void rejectsNegativePaycheckAmountsFromRepositoryTotals() {
    assertThatThrownBy(() -> calculator.calculateFromTotals(-1, 0, 0, 0, 0, 0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Paycheck amount must not be negative");
  }

  @ParameterizedTest
  @MethodSource("negativeRepositoryTotals")
  void rejectsNegativeRepositoryTotals(
      long allocated,
      long posted,
      long processing,
      long notPaid,
      long postedCount,
      long processingCount,
      long notPaidCount) {
    assertThatThrownBy(
            () ->
                calculator.calculateFromTotals(
                    10000,
                    allocated,
                    posted,
                    processing,
                    notPaid,
                    postedCount,
                    processingCount,
                    notPaidCount))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Paycheck totals must not be negative");
  }

  @Test
  void rejectsRepositoryTotalsThatDoNotMatchAllocatedAmount() {
    assertThatThrownBy(() -> calculator.calculateFromTotals(10000, 6000, 2000, 3000, 999, 1, 2, 3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Paycheck status totals must match allocation total");
  }

  private static Stream<Arguments> negativeRepositoryTotals() {
    return Stream.of(
        Arguments.of(-1, 0, 0, 0, 0, 0, 0),
        Arguments.of(0, -1, 0, 0, 0, 0, 0),
        Arguments.of(0, 0, -1, 0, 0, 0, 0),
        Arguments.of(0, 0, 0, -1, 0, 0, 0),
        Arguments.of(0, 0, 0, 0, -1, 0, 0),
        Arguments.of(0, 0, 0, 0, 0, -1, 0),
        Arguments.of(0, 0, 0, 0, 0, 0, -1));
  }
}
