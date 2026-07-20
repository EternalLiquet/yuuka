import { z } from 'zod';

const uuid = z.string().uuid();
const instant = z.string().datetime({ offset: true });
const date = z.string().regex(/^\d{4}-\d{2}-\d{2}$/);
const minor = z.number().int().safe();

export const entryStatusSchema = z.enum(['NOT_PAID', 'PROCESSING', 'POSTED']);
export type EntryStatus = z.infer<typeof entryStatusSchema>;

export const entryTypeSchema = z.enum(['BILL', 'SPENDING_BUCKET', 'SINKING_FUND']);
export type EntryType = z.infer<typeof entryTypeSchema>;

export const entryPaymentMethodSchema = z.enum(['AUTOPAY', 'MANUAL']);
export type EntryPaymentMethod = z.infer<typeof entryPaymentMethodSchema>;

export const searchScopeSchema = z.enum(['ALL', 'ACTIVE', 'HISTORY']);
export type SearchScope = z.infer<typeof searchScopeSchema>;

export const paycheckContextSchema = z.enum(['ACTIVE', 'HISTORY']);
export type PaycheckContext = z.infer<typeof paycheckContextSchema>;

export const spendingBucketPerformanceSummarySchema = z.object({
  budgetedMinor: minor.nonnegative(),
  spentMinor: minor.nonnegative(),
  netMinor: minor,
});
export type SpendingBucketPerformanceSummary = z.infer<
  typeof spendingBucketPerformanceSummarySchema
>;

export const entrySchema = z.object({
  id: uuid,
  paycheckId: uuid,
  paybackId: uuid.nullable(),
  sinkingFundId: uuid.nullable().optional(),
  entryType: entryTypeSchema,
  paymentMethod: entryPaymentMethodSchema.nullable(),
  name: z.string(),
  amountMinor: minor.nonnegative(),
  status: entryStatusSchema,
  position: z.number().int().nonnegative(),
  dueDate: date.nullable(),
  accountName: z.string().nullable(),
  payee: z.string().nullable(),
  notes: z.string().nullable(),
  targetMinor: minor.nonnegative().nullable(),
  targetDate: date.nullable(),
  sourceRecurringBillDefinitionId: uuid.nullable(),
  sourceRecurringOccurrenceDate: date.nullable(),
  sourceExpenseLedgerId: uuid.nullable().optional(),
  spentMinor: minor.nullable(),
  remainingMinor: minor.nullable(),
  overBudget: z.boolean().nullable(),
  createdAt: instant,
  updatedAt: instant,
  version: z.number().int().nonnegative(),
});
export type Entry = z.infer<typeof entrySchema>;

export const paycheckSchema = z.object({
  id: uuid,
  name: z.string(),
  source: z.string().nullable(),
  amountMinor: minor.nonnegative(),
  incomeDate: date,
  state: z.enum(['ACTIVE', 'CLOSED', 'ARCHIVED']),
  templateSourceId: uuid.nullable(),
  notes: z.string().nullable(),
  allocatedMinor: minor,
  unallocatedMinor: minor,
  allocationPercent: z.number(),
  postedMinor: minor,
  processingMinor: minor,
  notPaidMinor: minor,
  completionPercent: z.number(),
  postedCount: z.number().int().nonnegative(),
  processingCount: z.number().int().nonnegative(),
  notPaidCount: z.number().int().nonnegative(),
  requiresAttention: z.boolean(),
  spendingBucketPerformance: spendingBucketPerformanceSummarySchema.nullable(),
  entries: z.array(entrySchema),
  createdAt: instant,
  updatedAt: instant,
  closedAt: instant.nullable(),
  reopenedAt: instant.nullable(),
  archivedAt: instant.nullable(),
  version: z.number().int().nonnegative(),
});
export type Paycheck = z.infer<typeof paycheckSchema>;

export const rollingSpendingBucketPerformanceSchema = z.object({
  asOfDate: date,
  windowStartDate: date,
  windowEndDate: date,
  paycheckCount: z.number().int().nonnegative(),
  summary: spendingBucketPerformanceSummarySchema.nullable(),
});
export type RollingSpendingBucketPerformance = z.infer<
  typeof rollingSpendingBucketPerformanceSchema
>;

export const dashboardAttentionKindSchema = z.enum([
  'MANUAL_BILL_NOT_PAID',
  'UNALLOCATED_PAYCHECK',
  'PROCESSING_ENTRY',
  'OVER_BUDGET_BUCKET',
  'FINALIZED_EXPENSE_LEDGER',
]);
export type DashboardAttentionKind = z.infer<typeof dashboardAttentionKindSchema>;

export const dashboardAttentionItemSchema = z.object({
  kind: dashboardAttentionKindSchema,
  paycheckId: uuid.nullable(),
  entryId: uuid.nullable(),
  expenseLedgerId: uuid.nullable(),
  name: z.string(),
  amountMinor: minor.nonnegative(),
  dueDate: date.nullable(),
  attentionSinceDate: date.nullable(),
});
export type DashboardAttentionItem = z.infer<typeof dashboardAttentionItemSchema>;

export const dashboardPaycheckPreviewSchema = z.object({
  paycheckId: uuid,
  name: z.string(),
  incomeDate: date,
  amountMinor: minor.nonnegative(),
  unallocatedMinor: minor.nonnegative(),
  notPaidCount: z.number().int().nonnegative(),
  processingCount: z.number().int().nonnegative(),
});
export type DashboardPaycheckPreview = z.infer<typeof dashboardPaycheckPreviewSchema>;

export const dashboardSummarySchema = z.object({
  asOfDate: date,
  needsAttention: z.array(dashboardAttentionItemSchema).max(5),
  active: z.object({
    paycheckCount: z.number().int().nonnegative(),
    totalUnallocatedMinor: minor.nonnegative(),
    notPaidEntryCount: z.number().int().nonnegative(),
    processingEntryCount: z.number().int().nonnegative(),
    previews: z.array(dashboardPaycheckPreviewSchema).max(2),
  }),
  paybacks: z.object({
    totalRemainingMinor: minor.nonnegative(),
    activeCount: z.number().int().nonnegative(),
  }),
  plannedSavings: z.object({
    totalActiveReservedBalanceMinor: minor.nonnegative(),
    activeCount: z.number().int().nonnegative(),
  }),
  expenseLists: z.object({
    openCount: z.number().int().nonnegative(),
    finalizedCount: z.number().int().nonnegative(),
  }),
});
export type DashboardSummary = z.infer<typeof dashboardSummarySchema>;

export const paybackStateSchema = z.enum(['ACTIVE', 'PAID_OFF']);
export type PaybackState = z.infer<typeof paybackStateSchema>;

export const paybackSchema = z.object({
  id: uuid,
  name: z.string(),
  originalAmountMinor: minor.positive(),
  openingRemainingAmountMinor: minor.nonnegative(),
  repaidMinor: minor.nonnegative(),
  remainingMinor: minor.nonnegative(),
  progressPercent: z.number(),
  borrowedDate: date,
  source: z.string().nullable(),
  notes: z.string().nullable(),
  sourceExpenseLedgerId: uuid.nullable().optional(),
  state: paybackStateSchema,
  position: z.number().int().nonnegative(),
  repaymentCount: z.number().int().nonnegative(),
  createdAt: instant,
  updatedAt: instant,
  version: z.number().int().nonnegative(),
});
export type Payback = z.infer<typeof paybackSchema>;

export const paybackSummarySchema = z.object({
  totalRemainingMinor: minor.nonnegative(),
  totalOriginalMinor: minor.nonnegative(),
  totalRepaidMinor: minor.nonnegative(),
  activeCount: z.number().int().nonnegative(),
});
export type PaybackSummary = z.infer<typeof paybackSummarySchema>;

export const paybackListSchema = z.object({
  summary: paybackSummarySchema,
  items: z.array(paybackSchema),
});
export type PaybackList = z.infer<typeof paybackListSchema>;

export const paybackRepaymentSchema = z.object({
  id: uuid,
  paybackId: uuid,
  entryId: uuid,
  amountMinor: minor.positive(),
  paycheckName: z.string(),
  paycheckIncomeDate: date,
  entryName: z.string(),
  entryStatus: entryStatusSchema,
  appliedAt: instant,
  reversedAt: instant.nullable(),
  version: z.number().int().nonnegative(),
});
export type PaybackRepayment = z.infer<typeof paybackRepaymentSchema>;

export const sinkingFundStateSchema = z.enum(['ACTIVE', 'ARCHIVED']);
export type SinkingFundState = z.infer<typeof sinkingFundStateSchema>;

export const sinkingFundSchema = z.object({
  id: uuid,
  name: z.string(),
  targetMinor: minor.nonnegative().nullable(),
  targetDate: date.nullable(),
  notes: z.string().nullable(),
  state: sinkingFundStateSchema,
  position: z.number().int().nonnegative(),
  currentBalanceMinor: minor.nonnegative(),
  remainingTargetMinor: minor.nullable(),
  progressPercent: z.number().nullable(),
  transactionCount: z.number().int().nonnegative(),
  createdAt: instant,
  updatedAt: instant,
  archivedAt: instant.nullable(),
  version: z.number().int().nonnegative(),
});
export type SinkingFund = z.infer<typeof sinkingFundSchema>;

export const sinkingFundSummarySchema = z.object({
  totalActiveBalanceMinor: minor.nonnegative(),
  activeCount: z.number().int().nonnegative(),
  archivedCount: z.number().int().nonnegative(),
});
export type SinkingFundSummary = z.infer<typeof sinkingFundSummarySchema>;

export const sinkingFundListSchema = z.object({
  summary: sinkingFundSummarySchema,
  items: z.array(sinkingFundSchema),
});
export type SinkingFundList = z.infer<typeof sinkingFundListSchema>;

export const sinkingFundTransactionTypeSchema = z.enum([
  'OPENING_BALANCE',
  'CONTRIBUTION',
  'WITHDRAWAL',
]);
export type SinkingFundTransactionType = z.infer<typeof sinkingFundTransactionTypeSchema>;

export const sinkingFundTransactionSchema = z.object({
  id: uuid,
  sinkingFundId: uuid,
  entryId: uuid.nullable(),
  transactionType: sinkingFundTransactionTypeSchema,
  amountMinor: minor.positive(),
  effectiveDate: date,
  reason: z.string().nullable(),
  notes: z.string().nullable(),
  paycheckName: z.string().nullable(),
  paycheckIncomeDate: date.nullable(),
  entryName: z.string().nullable(),
  entryStatus: entryStatusSchema.nullable(),
  reversedAt: instant.nullable(),
  reversalReason: z.string().nullable(),
  createdAt: instant,
  updatedAt: instant,
  version: z.number().int().nonnegative(),
});
export type SinkingFundTransaction = z.infer<typeof sinkingFundTransactionSchema>;

export const expenseLedgerStateSchema = z.enum(['OPEN', 'FINALIZED', 'SETTLED']);
export type ExpenseLedgerState = z.infer<typeof expenseLedgerStateSchema>;

export const expenseLedgerSettlementTypeSchema = z.enum(['BILL', 'PAYBACK']);
export type ExpenseLedgerSettlementType = z.infer<typeof expenseLedgerSettlementTypeSchema>;

export const expenseLedgerItemSchema = z.object({
  id: uuid,
  ledgerId: uuid,
  name: z.string().nullable(),
  merchant: z.string().nullable(),
  amountMinor: minor.positive(),
  expenseDate: date,
  notes: z.string().nullable(),
  createdAt: instant,
  updatedAt: instant,
  version: z.number().int().nonnegative(),
});
export type ExpenseLedgerItem = z.infer<typeof expenseLedgerItemSchema>;

export const expenseLedgerSettlementSchema = z.object({
  id: uuid,
  ledgerId: uuid,
  settlementType: expenseLedgerSettlementTypeSchema,
  settlementAmountMinor: minor.positive(),
  targetId: uuid,
  targetPaycheckId: uuid.nullable(),
  settledAt: instant,
  createdAt: instant,
});
export type ExpenseLedgerSettlement = z.infer<typeof expenseLedgerSettlementSchema>;

export const expenseLedgerSchema = z.object({
  id: uuid,
  name: z.string(),
  notes: z.string().nullable(),
  state: expenseLedgerStateSchema,
  totalMinor: minor.nonnegative(),
  itemCount: z.number().int().nonnegative(),
  latestExpenseDate: date.nullable(),
  settlement: expenseLedgerSettlementSchema.nullable(),
  items: z.array(expenseLedgerItemSchema),
  finalizedAt: instant.nullable(),
  reopenedAt: instant.nullable(),
  settledAt: instant.nullable(),
  createdAt: instant,
  updatedAt: instant,
  version: z.number().int().nonnegative(),
});
export type ExpenseLedger = z.infer<typeof expenseLedgerSchema>;

export const expenseLedgerSettlementResultSchema = z.object({
  ledger: expenseLedgerSchema,
  billEntry: entrySchema.nullable(),
  payback: paybackSchema.nullable(),
});
export type ExpenseLedgerSettlementResult = z.infer<typeof expenseLedgerSettlementResultSchema>;

export const templateEntrySchema = z.object({
  id: uuid,
  entryType: entryTypeSchema,
  paymentMethod: entryPaymentMethodSchema.nullable(),
  name: z.string(),
  defaultAmountMinor: minor.nonnegative(),
  position: z.number().int().nonnegative(),
  defaultDueOffsetDays: z.number().int().nullable(),
  accountName: z.string().nullable(),
  payee: z.string().nullable(),
  notes: z.string().nullable(),
  targetMinor: minor.nonnegative().nullable(),
  targetDate: date.nullable(),
  createdAt: instant,
  updatedAt: instant,
  version: z.number().int().nonnegative(),
});
export type TemplateEntry = z.infer<typeof templateEntrySchema>;

export const templateSchema = z.object({
  id: uuid,
  name: z.string(),
  description: z.string().nullable(),
  archived: z.boolean(),
  entryCount: z.number().int().nonnegative(),
  defaultTotalMinor: minor.nonnegative(),
  entries: z.array(templateEntrySchema),
  createdAt: instant,
  updatedAt: instant,
  archivedAt: instant.nullable(),
  version: z.number().int().nonnegative(),
});
export type BudgetTemplate = z.infer<typeof templateSchema>;

export const bucketTransactionSchema = z.object({
  id: uuid,
  entryId: uuid,
  amountMinor: minor.positive(),
  description: z.string().nullable(),
  notes: z.string().nullable(),
  effectiveDate: date,
  createdAt: instant,
  updatedAt: instant,
  version: z.number().int().nonnegative(),
});
export type BucketTransaction = z.infer<typeof bucketTransactionSchema>;

export const statusEventSchema = z.object({
  id: uuid,
  entryId: uuid,
  fromStatus: entryStatusSchema.nullable(),
  toStatus: entryStatusSchema,
  effectiveAt: instant,
  recordedAt: instant,
  note: z.string().nullable(),
});
export type StatusEvent = z.infer<typeof statusEventSchema>;

export const auditEventSchema = z.object({
  id: uuid,
  entityType: z.string(),
  entityId: uuid,
  action: z.string(),
  effectiveAt: instant.nullable(),
  recordedAt: instant,
  beforeData: z.unknown().nullable(),
  afterData: z.unknown().nullable(),
  metadata: z.unknown().nullable(),
});
export type AuditEvent = z.infer<typeof auditEventSchema>;

export const entrySearchResultSchema = z.object({
  kind: z.literal('PAYCHECK_ENTRY'),
  entryId: uuid,
  paycheckId: uuid,
  entryName: z.string(),
  amountMinor: minor.nonnegative(),
  entryType: entryTypeSchema,
  paymentMethod: entryPaymentMethodSchema.nullable(),
  status: entryStatusSchema,
  paycheckName: z.string(),
  paycheckIncomeDate: date,
  paycheckContext: paycheckContextSchema,
});
export type EntrySearchResult = z.infer<typeof entrySearchResultSchema>;

export const versionResponseSchema = z
  .object({
    version: z.string().trim().min(1),
  })
  .strict();
export type VersionResponse = z.infer<typeof versionResponseSchema>;

export const meSchema = z.object({
  id: uuid,
  email: z.string().email(),
  displayName: z.string().nullable(),
  currencyCode: z.string().length(3),
  timezone: z.string(),
  recurringBillSuggestionDays: z.number().int().min(1).max(31),
  createdAt: instant,
  updatedAt: instant,
});
export type Me = z.infer<typeof meSchema>;

export const recurringBillRecurrenceTypeSchema = z.literal('MONTHLY');
export const recurringBillStatusFilterSchema = z.enum(['ACTIVE', 'INACTIVE', 'ALL']);
export type RecurringBillStatusFilter = z.infer<typeof recurringBillStatusFilterSchema>;

export const recurringBillSchema = z.object({
  id: uuid,
  name: z.string(),
  typicalAmountMinor: minor.nonnegative(),
  paymentMethod: entryPaymentMethodSchema,
  recurrenceType: recurringBillRecurrenceTypeSchema,
  dueDay: z.number().int().min(1).max(31),
  accountName: z.string().nullable(),
  payee: z.string().nullable(),
  notes: z.string().nullable(),
  active: z.boolean(),
  createdAt: instant,
  updatedAt: instant,
  version: z.number().int().nonnegative(),
});
export type RecurringBill = z.infer<typeof recurringBillSchema>;

export const recurringBillListSchema = z.object({ items: z.array(recurringBillSchema) });

export const recurringBillImportSummarySchema = z.object({
  entryId: uuid,
  paycheckId: uuid,
  paycheckName: z.string(),
  status: entryStatusSchema,
});

export const recurringBillOccurrenceSchema = z.object({
  definitionId: uuid,
  definitionVersion: z.number().int().nonnegative(),
  occurrenceDate: date,
  name: z.string(),
  typicalAmountMinor: minor.nonnegative(),
  paymentMethod: entryPaymentMethodSchema,
  accountName: z.string().nullable(),
  payee: z.string().nullable(),
  notes: z.string().nullable(),
  importCount: z.number().int().nonnegative(),
  imports: z.array(recurringBillImportSummarySchema),
});
export type RecurringBillOccurrence = z.infer<typeof recurringBillOccurrenceSchema>;

export const recurringBillTimelineSchema = z.object({
  from: date,
  through: date,
  items: z.array(recurringBillOccurrenceSchema),
});
export type RecurringBillTimeline = z.infer<typeof recurringBillTimelineSchema>;

export function pageSchema<T extends z.ZodTypeAny>(item: T) {
  return z.object({
    items: z.array(item),
    page: z.number().int().nonnegative(),
    size: z.number().int().nonnegative(),
    totalItems: z.number().int().nonnegative(),
    totalPages: z.number().int().nonnegative(),
    hasNext: z.boolean(),
  });
}

export type Page<T> = {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
  hasNext: boolean;
};
