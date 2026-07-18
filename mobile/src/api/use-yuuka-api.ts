import { useMemo } from 'react';
import { z } from 'zod';

import { useAuth } from '@/auth/auth-provider';

import { expectNoContent, parseApiResponse } from './api-client';
import {
  auditEventSchema,
  bucketTransactionSchema,
  entrySchema,
  expenseLedgerItemSchema,
  expenseLedgerSchema,
  expenseLedgerSettlementResultSchema,
  entrySearchResultSchema,
  meSchema,
  pageSchema,
  paybackListSchema,
  paybackRepaymentSchema,
  paybackSchema,
  paycheckSchema,
  rollingSpendingBucketPerformanceSchema,
  recurringBillListSchema,
  recurringBillSchema,
  recurringBillTimelineSchema,
  sinkingFundListSchema,
  sinkingFundSchema,
  sinkingFundTransactionSchema,
  statusEventSchema,
  templateEntrySchema,
  templateSchema,
} from './contracts';
import type {
  EntryPaymentMethod,
  ExpenseLedgerState,
  EntrySearchResult,
  Page,
  RecurringBillStatusFilter,
  SearchScope,
} from './contracts';

export type EntryPayload = {
  accountName?: string | null;
  amountMinor: number;
  dueDate?: string | null;
  entryType: 'BILL' | 'SPENDING_BUCKET' | 'SINKING_FUND';
  name: string;
  notes?: string | null;
  paymentMethod?: EntryPaymentMethod | null;
  payee?: string | null;
  paybackId?: string | null;
  sinkingFundId?: string | null;
  targetDate?: string | null;
  targetMinor?: number | null;
  sourceRecurringBillDefinitionId?: string | null;
  sourceRecurringOccurrenceDate?: string | null;
  version?: number;
};

export type RecurringBillPayload = {
  accountName?: string | null;
  dueDay: number;
  name: string;
  notes?: string | null;
  payee?: string | null;
  paymentMethod?: EntryPaymentMethod;
  typicalAmountMinor: number;
  version?: number;
};

export type RecurringBillImportPayload = {
  amountMinor: number;
  definitionId: string;
  definitionVersion: number;
  occurrenceDate: string;
  updateTypicalAmount: boolean;
};

export type TemplateEntryPayload = {
  accountName?: string | null;
  defaultAmountMinor: number;
  defaultDueOffsetDays?: number | null;
  entryType: EntryPayload['entryType'];
  name: string;
  notes?: string | null;
  paymentMethod?: EntryPaymentMethod | null;
  payee?: string | null;
  targetDate?: string | null;
  targetMinor?: number | null;
  version?: number;
};

export type ExpenseLedgerItemPayload = {
  amountMinor: number;
  expenseDate?: string | null;
  merchant?: string | null;
  name?: string | null;
  notes?: string | null;
  version?: number;
};

export function useYuukaApi() {
  const { authenticatedRequest } = useAuth();

  return useMemo(() => {
    const get = async <T>(path: string, schema: z.ZodType<T>) =>
      parseApiResponse(await authenticatedRequest(path), schema);
    const send = async <T>(
      path: string,
      method: 'DELETE' | 'PATCH' | 'POST' | 'PUT',
      body: unknown,
      schema: z.ZodType<T>,
    ) =>
      parseApiResponse(
        await authenticatedRequest(path, {
          method,
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        }),
        schema,
      );
    const remove = async (path: string) =>
      expectNoContent(await authenticatedRequest(path, { method: 'DELETE' }));

    return {
      me: () => get('/me', meSchema),
      updateOwnerSettings: (recurringBillSuggestionDays: number) =>
        send('/me/settings', 'PATCH', { recurringBillSuggestionDays }, meSchema),
      recurringBills: (status: RecurringBillStatusFilter = 'ACTIVE', search = '') => {
        const params = new URLSearchParams({ status });
        if (search.trim()) params.set('search', search.trim());
        return get(`/recurring-bills?${params.toString()}`, recurringBillListSchema);
      },
      recurringBill: (id: string) => get(`/recurring-bills/${id}`, recurringBillSchema),
      createRecurringBill: (body: RecurringBillPayload) =>
        send('/recurring-bills', 'POST', body, recurringBillSchema),
      updateRecurringBill: (id: string, body: RecurringBillPayload & { version: number }) =>
        send(`/recurring-bills/${id}`, 'PUT', body, recurringBillSchema),
      activateRecurringBill: (id: string, version: number) =>
        send(`/recurring-bills/${id}/activate`, 'POST', { version }, recurringBillSchema),
      deactivateRecurringBill: (id: string, version: number) =>
        send(`/recurring-bills/${id}/deactivate`, 'POST', { version }, recurringBillSchema),
      deleteRecurringBill: (id: string, version: number) =>
        remove(`/recurring-bills/${id}?version=${version}`),
      recurringBillTimeline: (from: string, through: string) =>
        get(
          `/recurring-bills/timeline?from=${encodeURIComponent(from)}&through=${encodeURIComponent(through)}`,
          recurringBillTimelineSchema,
        ),
      importRecurringBills: (
        paycheckId: string,
        paycheckVersion: number,
        items: RecurringBillImportPayload[],
      ) =>
        send(
          `/paychecks/${paycheckId}/recurring-bill-imports`,
          'POST',
          { paycheckVersion, items },
          paycheckSchema,
        ),
      activePaychecks: () => get('/paychecks/active?size=100', pageSchema(paycheckSchema)),
      rollingSpendingBucketPerformance: (days: 30 | 90 = 30) =>
        get(
          `/spending-buckets/performance/rolling?days=${days}`,
          rollingSpendingBucketPerformanceSchema,
        ),
      historyPaychecks: (query = '') =>
        get(`/paychecks/history?size=100${query}`, pageSchema(paycheckSchema)),
      searchEntries: ({
        amountMinor,
        page = 0,
        query,
        scope = 'ALL',
        size = 20,
      }: {
        amountMinor?: number;
        page?: number;
        query?: string;
        scope?: SearchScope;
        size?: number;
      }): Promise<Page<EntrySearchResult>> => {
        const params = new URLSearchParams({ page: String(page), scope, size: String(size) });
        if (query?.trim()) params.set('query', query.trim());
        if (amountMinor != null) params.set('amountMinor', String(amountMinor));
        return get(`/search/entries?${params.toString()}`, pageSchema(entrySearchResultSchema));
      },
      paycheck: (id: string) => get(`/paychecks/${id}`, paycheckSchema),
      paybacks: () => get('/paybacks', paybackListSchema),
      payback: (id: string) => get(`/paybacks/${id}`, paybackSchema),
      createPayback: (body: {
        borrowedDate: string;
        name: string;
        notes?: string | null;
        openingRemainingAmountMinor: number;
        originalAmountMinor: number;
        source?: string | null;
      }) => send('/paybacks', 'POST', body, paybackSchema),
      updatePayback: (
        id: string,
        body: {
          borrowedDate: string;
          name: string;
          notes?: string | null;
          openingRemainingAmountMinor: number;
          originalAmountMinor: number;
          source?: string | null;
          version: number;
        },
      ) => send(`/paybacks/${id}`, 'PATCH', body, paybackSchema),
      deletePayback: (id: string, version: number) => remove(`/paybacks/${id}?version=${version}`),
      reorderPaybacks: (paybackIds: string[]) =>
        send('/paybacks/reorder', 'POST', { paybackIds }, paybackListSchema),
      paybackRepayments: (id: string) =>
        get(`/paybacks/${id}/repayments?size=100`, pageSchema(paybackRepaymentSchema)),
      sinkingFunds: (includeArchived = false) =>
        get(`/sinking-funds?includeArchived=${includeArchived}`, sinkingFundListSchema),
      sinkingFund: (id: string) => get(`/sinking-funds/${id}`, sinkingFundSchema),
      createSinkingFund: (body: {
        name: string;
        notes?: string | null;
        openingBalanceMinor?: number | null;
        targetDate?: string | null;
        targetMinor?: number | null;
      }) => send('/sinking-funds', 'POST', body, sinkingFundSchema),
      updateSinkingFund: (
        id: string,
        body: {
          name: string;
          notes?: string | null;
          targetDate?: string | null;
          targetMinor?: number | null;
          version: number;
        },
      ) => send(`/sinking-funds/${id}`, 'PATCH', body, sinkingFundSchema),
      archiveSinkingFund: (id: string, version: number, confirmPositiveBalance = false) =>
        send(
          `/sinking-funds/${id}/archive`,
          'POST',
          { version, confirmPositiveBalance },
          sinkingFundSchema,
        ),
      restoreSinkingFund: (id: string, version: number) =>
        send(`/sinking-funds/${id}/restore`, 'POST', { version }, sinkingFundSchema),
      reorderSinkingFunds: (sinkingFundIds: string[]) =>
        send('/sinking-funds/reorder', 'POST', { sinkingFundIds }, sinkingFundListSchema),
      sinkingFundTransactions: (id: string, page: number, size: number) =>
        get(
          `/sinking-funds/${id}/transactions?page=${page}&size=${size}`,
          pageSchema(sinkingFundTransactionSchema),
        ),
      withdrawSinkingFund: (
        id: string,
        body: {
          amountMinor: number;
          effectiveDate?: string | null;
          notes?: string | null;
          reason: string;
          version: number;
        },
      ) => send(`/sinking-funds/${id}/withdrawals`, 'POST', body, sinkingFundTransactionSchema),
      reverseSinkingFundWithdrawal: (
        transactionId: string,
        body: { reason: string; version: number },
      ) =>
        send(
          `/sinking-fund-transactions/${transactionId}/reverse`,
          'POST',
          body,
          sinkingFundTransactionSchema,
        ),
      expenseLedgers: (state: ExpenseLedgerState, page: number, size: number) => {
        const params = new URLSearchParams({ page: String(page), size: String(size) });
        if (state) params.set('state', state);
        return get(`/expense-ledgers?${params.toString()}`, pageSchema(expenseLedgerSchema));
      },
      expenseLedger: (id: string) => get(`/expense-ledgers/${id}`, expenseLedgerSchema),
      createExpenseLedger: (body: { name: string; notes?: string | null }) =>
        send('/expense-ledgers', 'POST', body, expenseLedgerSchema),
      updateExpenseLedger: (
        id: string,
        body: { name: string; notes?: string | null; version: number },
      ) => send(`/expense-ledgers/${id}`, 'PATCH', body, expenseLedgerSchema),
      deleteExpenseLedger: (id: string, version: number) =>
        remove(`/expense-ledgers/${id}?version=${version}`),
      addExpenseLedgerItem: (ledgerId: string, body: ExpenseLedgerItemPayload) =>
        send(`/expense-ledgers/${ledgerId}/items`, 'POST', body, expenseLedgerItemSchema),
      updateExpenseLedgerItem: (
        itemId: string,
        body: ExpenseLedgerItemPayload & { version: number },
      ) => send(`/expense-ledgers/items/${itemId}`, 'PATCH', body, expenseLedgerItemSchema),
      deleteExpenseLedgerItem: (itemId: string, version: number) =>
        remove(`/expense-ledgers/items/${itemId}?version=${version}`),
      finalizeExpenseLedger: (id: string, version: number) =>
        send(`/expense-ledgers/${id}/finalize`, 'POST', { version }, expenseLedgerSchema),
      reopenExpenseLedger: (id: string, version: number) =>
        send(`/expense-ledgers/${id}/reopen`, 'POST', { version }, expenseLedgerSchema),
      settleExpenseLedgerAsBill: (
        id: string,
        body: {
          accountName?: string | null;
          dueDate?: string | null;
          ledgerVersion: number;
          name?: string | null;
          payee?: string | null;
          paycheckId: string;
          paymentMethod?: EntryPaymentMethod | null;
          notes?: string | null;
        },
      ) =>
        send(
          `/expense-ledgers/${id}/settle/bill`,
          'POST',
          body,
          expenseLedgerSettlementResultSchema,
        ),
      settleExpenseLedgerAsPayback: (
        id: string,
        body: {
          borrowedDate?: string | null;
          ledgerVersion: number;
          name?: string | null;
          notes?: string | null;
          source?: string | null;
        },
      ) =>
        send(
          `/expense-ledgers/${id}/settle/payback`,
          'POST',
          body,
          expenseLedgerSettlementResultSchema,
        ),
      createPaycheck: (body: {
        amountMinor: number;
        incomeDate: string;
        name: string;
        notes?: string | null;
        source?: string | null;
      }) => send('/paychecks', 'POST', body, paycheckSchema),
      createPaycheckFromTemplate: (body: {
        amountMinor: number;
        entries?: EntryPayload[];
        incomeDate: string;
        name?: string;
        notes?: string | null;
        source?: string | null;
        templateId: string;
      }) => send('/paychecks/from-template', 'POST', body, paycheckSchema),
      createPaycheckFromDraft: (body: {
        amountMinor: number;
        entries: EntryPayload[];
        incomeDate: string;
        name: string;
        notes?: string | null;
        source?: string | null;
      }) => send('/paychecks/from-draft', 'POST', body, paycheckSchema),
      updatePaycheck: (id: string, body: unknown) =>
        send(`/paychecks/${id}`, 'PATCH', body, paycheckSchema),
      closePaycheck: (id: string, version: number) =>
        send(`/paychecks/${id}/close`, 'POST', { version }, paycheckSchema),
      reopenPaycheck: (id: string, version: number) =>
        send(`/paychecks/${id}/reopen`, 'POST', { version }, paycheckSchema),
      archivePaycheck: (id: string, version: number) =>
        send(`/paychecks/${id}?version=${version}`, 'DELETE', undefined, paycheckSchema),
      allocateLeftover: (paycheckId: string, paycheckVersion: number) =>
        send(`/paychecks/${paycheckId}/leftover-entry`, 'POST', { paycheckVersion }, entrySchema),
      addEntry: (paycheckId: string, body: EntryPayload) =>
        send(`/paychecks/${paycheckId}/entries`, 'POST', body, entrySchema),
      updateEntry: (entryId: string, body: EntryPayload & { version: number }) =>
        send(`/entries/${entryId}`, 'PATCH', body, entrySchema),
      deleteEntry: (entryId: string, version: number) =>
        remove(`/entries/${entryId}?version=${version}`),
      changeStatus: (
        entryId: string,
        body: { effectiveAt: string; note?: string; toStatus: string; version: number },
      ) => send(`/entries/${entryId}/status`, 'POST', body, entrySchema),
      reorderEntries: (paycheckId: string, entryIds: string[], paycheckVersion: number) =>
        send(
          `/paychecks/${paycheckId}/entries/reorder`,
          'POST',
          { entryIds, paycheckVersion },
          paycheckSchema,
        ),
      statusHistory: (entryId: string) =>
        get(`/entries/${entryId}/status-history?size=100`, pageSchema(statusEventSchema)),
      paycheckAudit: (paycheckId: string) =>
        get(`/paychecks/${paycheckId}/audit?size=100`, pageSchema(auditEventSchema)),
      templates: (includeArchived = false) =>
        get(`/templates?includeArchived=${includeArchived}`, pageSchema(templateSchema)),
      template: (id: string) => get(`/templates/${id}`, templateSchema),
      createTemplate: (body: {
        description?: string | null;
        entries: TemplateEntryPayload[];
        name: string;
      }) => send('/templates', 'POST', body, templateSchema),
      updateTemplate: (id: string, body: unknown) =>
        send(`/templates/${id}`, 'PATCH', body, templateSchema),
      duplicateTemplate: (id: string, name?: string) =>
        send(`/templates/${id}/duplicate`, 'POST', { name }, templateSchema),
      archiveTemplate: (id: string, version: number) =>
        send(`/templates/${id}/archive`, 'POST', { version }, templateSchema),
      restoreTemplate: (id: string, version: number) =>
        send(`/templates/${id}/restore`, 'POST', { version }, templateSchema),
      addTemplateEntry: (id: string, body: TemplateEntryPayload) =>
        send(`/templates/${id}/entries`, 'POST', body, templateEntrySchema),
      updateTemplateEntry: (id: string, body: TemplateEntryPayload & { version: number }) =>
        send(`/template-entries/${id}`, 'PATCH', body, templateEntrySchema),
      deleteTemplateEntry: (id: string, version: number) =>
        remove(`/template-entries/${id}?version=${version}`),
      reorderTemplateEntries: (id: string, entryIds: string[], templateVersion: number) =>
        send(
          `/templates/${id}/entries/reorder`,
          'POST',
          { entryIds, templateVersion },
          templateSchema,
        ),
      bucketTransactions: (entryId: string, page: number, size: number) =>
        get(
          `/entries/${entryId}/bucket-transactions?page=${page}&size=${size}`,
          pageSchema(bucketTransactionSchema),
        ),
      addBucketTransaction: (
        entryId: string,
        body: { amountMinor: number; description?: string; notes?: string; effectiveDate: string },
      ) => send(`/entries/${entryId}/bucket-transactions`, 'POST', body, bucketTransactionSchema),
      updateBucketTransaction: (
        transactionId: string,
        body: {
          amountMinor: number;
          description?: string;
          notes?: string;
          effectiveDate: string;
          version: number;
        },
      ) => send(`/bucket-transactions/${transactionId}`, 'PATCH', body, bucketTransactionSchema),
      deleteBucketTransaction: (transactionId: string, version: number) =>
        remove(`/bucket-transactions/${transactionId}?version=${version}`),
    };
  }, [authenticatedRequest]);
}
