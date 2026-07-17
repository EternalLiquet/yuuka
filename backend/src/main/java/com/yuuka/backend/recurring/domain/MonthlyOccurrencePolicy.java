package com.yuuka.backend.recurring.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class MonthlyOccurrencePolicy {
  public LocalDate occurrence(YearMonth month, int dueDay) {
    if (dueDay < 1 || dueDay > 31) {
      throw new IllegalArgumentException("Due day must be between 1 and 31.");
    }
    return month.atDay(Math.min(dueDay, month.lengthOfMonth()));
  }

  public List<LocalDate> occurrences(LocalDate from, LocalDate through, int dueDay) {
    if (through.isBefore(from)) {
      throw new IllegalArgumentException("Timeline end must not be before its start.");
    }
    List<LocalDate> result = new ArrayList<>();
    YearMonth month = YearMonth.from(from);
    YearMonth finalMonth = YearMonth.from(through);
    while (!month.isAfter(finalMonth)) {
      LocalDate date = occurrence(month, dueDay);
      if (!date.isBefore(from) && !date.isAfter(through)) {
        result.add(date);
      }
      month = month.plusMonths(1);
    }
    return List.copyOf(result);
  }
}
