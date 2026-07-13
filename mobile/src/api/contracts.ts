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

export const entrySchema = z.object({
  id: uuid,
  paycheckId: uuid,
  paybackId: uuid.nullable(),
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
  entries: z.array(entrySchema),
  createdAt: instant,
  updatedAt: instant,
  closedAt: instant.nullable(),
  reopenedAt: instant.nullable(),
  archivedAt: instant.nullable(),
  version: z.number().int().nonnegative(),
});
export type Paycheck = z.infer<typeof paycheckSchema>;

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
  createdAt: instant,
  updatedAt: instant,
});
export type Me = z.infer<typeof meSchema>;

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
