package com.yuuka.backend.paycheck.application;

import com.yuuka.backend.audit.application.AuditService;
import com.yuuka.backend.common.api.BusinessRuleException;
import com.yuuka.backend.paycheck.api.dto.PaycheckResponse;
import com.yuuka.backend.paycheck.domain.Paycheck;
import com.yuuka.backend.paycheck.domain.PaycheckEntry;
import com.yuuka.backend.paycheck.domain.PaycheckMetrics;
import com.yuuka.backend.paycheck.domain.PaycheckState;
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class PaycheckLifecycleTransitions {
  private final JpaPaycheckRepository paychecks;
  private final PaycheckResponseAssembler responseAssembler;
  private final AuditService auditService;

  PaycheckLifecycleTransitions(
      JpaPaycheckRepository paychecks,
      PaycheckResponseAssembler responseAssembler,
      AuditService auditService) {
    this.paychecks = paychecks;
    this.responseAssembler = responseAssembler;
    this.auditService = auditService;
  }

  PaycheckResponse close(
      UUID ownerId,
      Paycheck paycheck,
      List<PaycheckEntry> liveEntries,
      Instant recordedAt,
      LocalDate asOfDate) {
    PaycheckMetrics metrics = responseAssembler.calculate(paycheck, liveEntries);
    if (!metrics.fullyAllocated() || !metrics.fullyPosted()) {
      throw new BusinessRuleException(
          "A paycheck can be closed only when fully allocated and fully Posted.");
    }
    PaycheckResponse before = responseAssembler.toResponse(paycheck, liveEntries, asOfDate);
    paycheck.close(recordedAt);
    paychecks.flush();
    PaycheckResponse after = responseAssembler.toResponse(paycheck, liveEntries, asOfDate);
    auditService.append(ownerId, "PAYCHECK", paycheck.getId(), "CLOSED", null, before, after, null);
    return after;
  }

  PaycheckResponse reopen(
      UUID ownerId,
      Paycheck paycheck,
      List<PaycheckEntry> liveEntries,
      Instant recordedAt,
      LocalDate asOfDate) {
    PaycheckResponse before = responseAssembler.toResponse(paycheck, liveEntries, asOfDate);
    paycheck.reopen(recordedAt);
    paychecks.flush();
    PaycheckResponse after = responseAssembler.toResponse(paycheck, liveEntries, asOfDate);
    auditService.append(
        ownerId, "PAYCHECK", paycheck.getId(), "REOPENED", null, before, after, null);
    return after;
  }

  PaycheckResponse archive(
      UUID ownerId,
      Paycheck paycheck,
      List<PaycheckEntry> liveEntries,
      Instant recordedAt,
      LocalDate asOfDate) {
    PaycheckResponse before = responseAssembler.toResponse(paycheck, liveEntries, asOfDate);
    paycheck.archive(recordedAt);
    paychecks.flush();
    PaycheckResponse after = responseAssembler.toResponse(paycheck, liveEntries, asOfDate);
    auditService.append(
        ownerId, "PAYCHECK", paycheck.getId(), "ARCHIVED", null, before, after, null);
    return after;
  }

  void closeAutomaticallyIfComplete(
      UUID ownerId,
      Paycheck paycheck,
      List<PaycheckEntry> liveEntries,
      Instant recordedAt,
      LocalDate asOfDate) {
    if (paycheck.getState() != PaycheckState.ACTIVE || paycheck.getReopenedAt() != null) {
      return;
    }
    PaycheckMetrics metrics = responseAssembler.calculate(paycheck, liveEntries);
    if (!metrics.fullyAllocated() || !metrics.fullyPosted()) {
      return;
    }

    PaycheckResponse before = responseAssembler.toResponse(paycheck, liveEntries, asOfDate);
    paycheck.close(recordedAt);
    paychecks.flush();
    PaycheckResponse after = responseAssembler.toResponse(paycheck, liveEntries, asOfDate);
    auditService.append(
        ownerId,
        "PAYCHECK",
        paycheck.getId(),
        "CLOSED",
        null,
        before,
        after,
        Map.of("automatic", true));
  }
}
