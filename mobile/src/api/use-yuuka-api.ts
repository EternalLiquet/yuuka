import { useMemo } from 'react';
import { z } from 'zod';

import { useAuth } from '@/auth/auth-provider';

import { expectNoContent, parseApiResponse } from './api-client';
import {
  auditEventSchema,
  bucketTransactionSchema,
  entrySchema,
  entrySearchResultSchema,
  meSchema,
  pageSchema,
  paybackListSchema,
  paybackRepaymentSchema,
  paybackSchema,
  paycheckSchema,
  statusEventSchema,
  templateEntrySchema,
  templateSchema,
} from './contracts';

export type EntryPayload = {
  accountName?: string | null;
  amountMinor: number;
  dueDate?: string | null;
  entryType: 'BILL' | 'SPENDING_BUCKET' | 'SINKING_FUND';
  name: string;
  notes?: string | null;
  payee?: string | null;
  paybackId?: string | null;
  targetDate?: string | null;
  targetMinor?: number | null;
  version?: number;
};

export type TemplateEntryPayload = {
  accountName?: string | null;
  defaultAmountMinor: number;
  defaultDueOffsetDays?: number | null;
  entryType: EntryPayload['entryType'];
  name: string;
  notes?: string | null;
  payee?: string | null;
  targetDate?: string | null;
  targetMinor?: number | null;
  version?: number;
};

export function useYuukaApi() {
  const { authenticatedRequest } = useAuth();

  return useMemo(() => {
    const get = async <T>(path: string, schema: z.ZodType<T>) =>
      parseApiResponse(await authenticatedRequest(path), schema);
    const send = async <T>(
      path: string,
      method: 'DELETE' | 'PATCH' | 'POST',
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
      activePaychecks: () => get('/paychecks/active?size=100', pageSchema(paycheckSchema)),
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
        scope?: 'ALL' | 'ACTIVE' | 'HISTORY';
        size?: number;
      }) => {
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
      bucketTransactions: (entryId: string) =>
        get(`/entries/${entryId}/bucket-transactions`, pageSchema(bucketTransactionSchema)),
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
