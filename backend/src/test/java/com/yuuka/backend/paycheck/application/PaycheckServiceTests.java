package com.yuuka.backend.paycheck.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yuuka.backend.audit.application.AuditService;
import com.yuuka.backend.auth.application.OwnerLocalDateService;
import com.yuuka.backend.bucket.application.SpendingBucketPerformanceService;
import com.yuuka.backend.bucket.domain.BucketCalculator;
import com.yuuka.backend.bucket.infrastructure.JpaBucketTransactionRepository;
import com.yuuka.backend.common.api.PageResponse;
import com.yuuka.backend.payback.application.PaybackService;
import com.yuuka.backend.paycheck.api.dto.PaycheckResponse;
import com.yuuka.backend.paycheck.domain.Paycheck;
import com.yuuka.backend.paycheck.domain.PaycheckCalculator;
import com.yuuka.backend.paycheck.domain.PaycheckState;
import com.yuuka.backend.paycheck.domain.StatusTransitionPolicy;
import com.yuuka.backend.paycheck.infrastructure.JpaEntryStatusEventRepository;
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckEntryRepository;
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckRepository;
import com.yuuka.backend.sinkingfund.application.SinkingFundService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class PaycheckServiceTests {
  private final JpaPaycheckRepository paychecks = mock(JpaPaycheckRepository.class);
  private final JpaPaycheckEntryRepository entries = mock(JpaPaycheckEntryRepository.class);
  private final JpaEntryStatusEventRepository statusEvents =
      mock(JpaEntryStatusEventRepository.class);
  private final JpaBucketTransactionRepository bucketTransactions =
      mock(JpaBucketTransactionRepository.class);
  private final StatusTransitionPolicy statusTransitionPolicy = mock(StatusTransitionPolicy.class);
  private final SpendingBucketPerformanceService spendingBucketPerformanceService =
      mock(SpendingBucketPerformanceService.class);
  private final OwnerLocalDateService ownerLocalDateService = mock(OwnerLocalDateService.class);
  private final PaybackService paybackService = mock(PaybackService.class);
  private final SinkingFundService sinkingFundService = mock(SinkingFundService.class);
  private final AuditService auditService = mock(AuditService.class);

  @Test
  void activeListResolvesOwnerLocalDateOnceForAllPaycheckResponses() {
    UUID ownerId = UUID.randomUUID();
    LocalDate asOfDate = LocalDate.parse("2026-07-14");
    Paycheck first = paycheck(ownerId, UUID.randomUUID(), "First");
    Paycheck second = paycheck(ownerId, UUID.randomUUID(), "Second");
    when(ownerLocalDateService.currentDate(ownerId)).thenReturn(asOfDate);
    when(paychecks.findActivePage(eq(ownerId), any()))
        .thenReturn(new PageImpl<>(List.of(first, second), PageRequest.of(0, 50), 2));
    when(entries.findAllLiveByPaycheckIds(eq(ownerId), any())).thenReturn(List.of());
    when(entries.aggregateMetricsByPaycheckIds(eq(ownerId), any())).thenReturn(List.of());
    when(spendingBucketPerformanceService.paycheckSummaries(eq(ownerId), any(), eq(asOfDate)))
        .thenReturn(Map.of());

    PageResponse<PaycheckResponse> response = service().active(ownerId, 0, 50);

    assertThat(response.items())
        .extracting(PaycheckResponse::name)
        .containsExactly("First", "Second");
    verify(ownerLocalDateService, times(1)).currentDate(ownerId);
    verify(spendingBucketPerformanceService, times(1))
        .paycheckSummaries(eq(ownerId), any(), eq(asOfDate));
    verify(spendingBucketPerformanceService, never())
        .paycheckSummary(eq(ownerId), any(UUID.class), eq(asOfDate));
  }

  private PaycheckService service() {
    PaycheckResponseAssembler responseAssembler =
        new PaycheckResponseAssembler(
            entries,
            bucketTransactions,
            new PaycheckCalculator(),
            new BucketCalculator(),
            spendingBucketPerformanceService,
            ownerLocalDateService);
    PaycheckValidationHelper validations = new PaycheckValidationHelper();
    return new PaycheckService(
        paychecks,
        entries,
        statusEvents,
        responseAssembler,
        new PaycheckEntryMutationHelper(),
        new PaycheckLifecycleTransitions(paychecks, responseAssembler, auditService),
        validations,
        statusTransitionPolicy,
        ownerLocalDateService,
        paybackService,
        sinkingFundService,
        auditService,
        Clock.fixed(Instant.parse("2026-07-15T02:00:00Z"), ZoneOffset.UTC));
  }

  private Paycheck paycheck(UUID ownerId, UUID paycheckId, String name) {
    Paycheck paycheck = mock(Paycheck.class);
    when(paycheck.getId()).thenReturn(paycheckId);
    when(paycheck.getOwnerId()).thenReturn(ownerId);
    when(paycheck.getName()).thenReturn(name);
    when(paycheck.getAmountMinor()).thenReturn(1000L);
    when(paycheck.getIncomeDate()).thenReturn(LocalDate.parse("2026-07-14"));
    when(paycheck.getState()).thenReturn(PaycheckState.ACTIVE);
    return paycheck;
  }
}
