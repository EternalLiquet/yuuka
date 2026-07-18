import {
  auditEventSchema,
  bucketTransactionSchema,
  entrySchema,
  entrySearchResultSchema,
  entryPaymentMethodSchema,
  entryStatusSchema,
  entryTypeSchema,
  meSchema,
  pageSchema,
  paycheckSchema,
  rollingSpendingBucketPerformanceSchema,
  sinkingFundListSchema,
  sinkingFundSchema,
  sinkingFundTransactionSchema,
  spendingBucketPerformanceSummarySchema,
  statusEventSchema,
  templateEntrySchema,
  templateSchema,
  versionResponseSchema,
} from '@/api/contracts';
import { formatYuukaVersion, formatYuukaVersionFooter } from '@/api/version-format';

const entry = {
  accountName: null,
  amountMinor: 5000,
  createdAt: '2026-07-10T12:00:00Z',
  dueDate: null,
  entryType: 'SPENDING_BUCKET',
  paymentMethod: null,
  id: '11111111-1111-4111-8111-111111111111',
  name: 'Work Food',
  notes: null,
  overBudget: false,
  paybackId: null,
  payee: null,
  paycheckId: '11111111-1111-4111-8111-111111111110',
  position: 0,
  remainingMinor: 2855,
  spentMinor: 2145,
  status: 'NOT_PAID',
  targetDate: null,
  targetMinor: null,
  sourceRecurringBillDefinitionId: null,
  sourceRecurringOccurrenceDate: null,
  sinkingFundId: null,
  updatedAt: '2026-07-10T12:30:00Z',
  version: 0,
};

const templateEntry = {
  accountName: null,
  createdAt: '2026-07-10T12:00:00Z',
  defaultAmountMinor: 5000,
  defaultDueOffsetDays: null,
  entryType: 'SPENDING_BUCKET',
  paymentMethod: null,
  id: '11111111-1111-4111-8111-111111111112',
  name: 'Work Food',
  notes: null,
  payee: null,
  position: 0,
  targetDate: null,
  targetMinor: null,
  updatedAt: '2026-07-10T12:30:00Z',
  version: 0,
};

describe('API response contracts', () => {
  it('parses representative domain payloads', () => {
    expect(entryStatusSchema.parse('POSTED')).toBe('POSTED');
    expect(entryTypeSchema.parse('BILL')).toBe('BILL');
    expect(entryPaymentMethodSchema.parse('MANUAL')).toBe('MANUAL');
    expect(entrySchema.parse(entry)).toMatchObject({ name: 'Work Food' });
    expect(
      paycheckSchema.parse({
        allocatedMinor: 5000,
        allocationPercent: 100,
        amountMinor: 5000,
        archivedAt: null,
        closedAt: null,
        completionPercent: 0,
        createdAt: '2026-07-10T12:00:00Z',
        entries: [entry],
        id: '11111111-1111-4111-8111-111111111110',
        incomeDate: '2026-07-10',
        name: 'UTILITIES 1/2',
        notPaidCount: 1,
        notPaidMinor: 5000,
        notes: null,
        postedCount: 0,
        postedMinor: 0,
        processingCount: 0,
        processingMinor: 0,
        reopenedAt: null,
        requiresAttention: true,
        spendingBucketPerformance: {
          budgetedMinor: 5000,
          netMinor: 2855,
          spentMinor: 2145,
        },
        source: null,
        state: 'ACTIVE',
        templateSourceId: null,
        unallocatedMinor: 0,
        updatedAt: '2026-07-10T12:30:00Z',
        version: 0,
      }),
    ).toMatchObject({ entries: [expect.objectContaining({ name: 'Work Food' })] });
    expect(templateEntrySchema.parse(templateEntry)).toMatchObject({ name: 'Work Food' });
    expect(
      spendingBucketPerformanceSummarySchema.parse({
        budgetedMinor: 5000,
        netMinor: -250,
        spentMinor: 5250,
      }),
    ).toMatchObject({ netMinor: -250 });
    expect(
      rollingSpendingBucketPerformanceSchema.parse({
        asOfDate: '2026-07-14',
        paycheckCount: 2,
        summary: {
          budgetedMinor: 12000,
          netMinor: 3000,
          spentMinor: 9000,
        },
        windowEndDate: '2026-07-14',
        windowStartDate: '2026-04-16',
      }),
    ).toMatchObject({ paycheckCount: 2 });
    expect(
      templateSchema.parse({
        archived: false,
        archivedAt: null,
        createdAt: '2026-07-10T12:00:00Z',
        defaultTotalMinor: 5000,
        description: null,
        entries: [templateEntry],
        entryCount: 1,
        id: '11111111-1111-4111-8111-111111111113',
        name: 'Weekly defaults',
        updatedAt: '2026-07-10T12:30:00Z',
        version: 0,
      }),
    ).toMatchObject({ entryCount: 1 });
  });

  it('parses secondary API resources and pages', () => {
    expect(
      bucketTransactionSchema.parse({
        amountMinor: 500,
        createdAt: '2026-07-10T12:00:00Z',
        description: 'Lunch',
        notes: 'Receipt saved',
        effectiveDate: '2026-07-10',
        entryId: entry.id,
        id: '11111111-1111-4111-8111-111111111120',
        updatedAt: '2026-07-10T12:30:00Z',
        version: 0,
      }),
    ).toMatchObject({ amountMinor: 500, notes: 'Receipt saved' });
    expect(
      statusEventSchema.parse({
        effectiveAt: '2026-07-10T12:00:00Z',
        entryId: entry.id,
        fromStatus: null,
        id: '11111111-1111-4111-8111-111111111121',
        note: null,
        recordedAt: '2026-07-10T12:00:00Z',
        toStatus: 'NOT_PAID',
      }),
    ).toMatchObject({ toStatus: 'NOT_PAID' });
    expect(
      auditEventSchema.parse({
        action: 'ENTRY_CREATED',
        afterData: entry,
        beforeData: null,
        effectiveAt: null,
        entityId: entry.id,
        entityType: 'PAYCHECK_ENTRY',
        id: '11111111-1111-4111-8111-111111111122',
        metadata: null,
        recordedAt: '2026-07-10T12:00:00Z',
      }),
    ).toMatchObject({ action: 'ENTRY_CREATED' });
    expect(
      meSchema.parse({
        createdAt: '2026-07-10T12:00:00Z',
        currencyCode: 'USD',
        displayName: null,
        email: 'owner@yuuka.local',
        id: '11111111-1111-4111-8111-111111111123',
        timezone: 'America/Indianapolis',
        recurringBillSuggestionDays: 7,
        updatedAt: '2026-07-10T12:00:00Z',
      }),
    ).toMatchObject({ email: 'owner@yuuka.local' });
    expect(
      entrySearchResultSchema.parse({
        amountMinor: 1399,
        entryId: entry.id,
        entryName: 'Work Food',
        entryType: 'SPENDING_BUCKET',
        paymentMethod: null,
        kind: 'PAYCHECK_ENTRY',
        paycheckContext: 'ACTIVE',
        paycheckId: entry.paycheckId,
        paycheckIncomeDate: '2026-07-10',
        paycheckName: 'UTILITIES 1/2',
        status: 'NOT_PAID',
      }),
    ).toMatchObject({ amountMinor: 1399, kind: 'PAYCHECK_ENTRY' });
    expect(
      pageSchema(entrySchema).parse({
        hasNext: false,
        items: [entry],
        page: 0,
        size: 20,
        totalItems: 1,
        totalPages: 1,
      }),
    ).toMatchObject({ items: [expect.objectContaining({ id: entry.id })] });
    const sinkingFund = {
      archivedAt: null,
      createdAt: '2026-07-10T12:00:00Z',
      currentBalanceMinor: 2500,
      id: '11111111-1111-4111-8111-111111111124',
      name: 'Vacation',
      notes: null,
      position: 0,
      progressPercent: 25,
      remainingTargetMinor: 7500,
      state: 'ACTIVE',
      targetDate: '2026-12-31',
      targetMinor: 10000,
      transactionCount: 2,
      updatedAt: '2026-07-10T12:30:00Z',
      version: 0,
    };
    expect(sinkingFundSchema.parse(sinkingFund)).toMatchObject({
      currentBalanceMinor: 2500,
      name: 'Vacation',
    });
    expect(
      sinkingFundListSchema.parse({
        items: [sinkingFund],
        summary: { activeCount: 1, archivedCount: 0, totalActiveBalanceMinor: 2500 },
      }),
    ).toMatchObject({ summary: { activeCount: 1 } });
    expect(
      sinkingFundTransactionSchema.parse({
        amountMinor: 2500,
        createdAt: '2026-07-10T12:00:00Z',
        effectiveDate: '2026-07-10',
        entryId: entry.id,
        entryName: 'Work Food',
        entryStatus: 'POSTED',
        id: '11111111-1111-4111-8111-111111111125',
        notes: null,
        paycheckIncomeDate: '2026-07-10',
        paycheckName: 'UTILITIES 1/2',
        reason: null,
        reversalReason: null,
        reversedAt: null,
        sinkingFundId: sinkingFund.id,
        transactionType: 'CONTRIBUTION',
        updatedAt: '2026-07-10T12:30:00Z',
        version: 0,
      }),
    ).toMatchObject({ transactionType: 'CONTRIBUTION' });
  });

  it('validates and formats backend version responses', () => {
    expect(versionResponseSchema.parse({ version: ' 1.2.3 ' })).toEqual({ version: '1.2.3' });
    expect(() => versionResponseSchema.parse({})).toThrow();
    expect(() => versionResponseSchema.parse({ version: '' })).toThrow();
    expect(() => versionResponseSchema.parse({ version: '   ' })).toThrow();
    expect(() => versionResponseSchema.parse({ version: 123 })).toThrow();

    expect(formatYuukaVersion('1.2.3')).toBe('v1.2.3');
    expect(formatYuukaVersion('v1.2.3')).toBe('v1.2.3');
    expect(formatYuukaVersion('0.0.0-dev')).toBe('0.0.0-dev');
    expect(formatYuukaVersion(' 1.2.3 ')).toBe('v1.2.3');
    expect(formatYuukaVersion('')).toBeNull();
    expect(formatYuukaVersionFooter(undefined)).toBe('Yuuka version unavailable');
  });
});
