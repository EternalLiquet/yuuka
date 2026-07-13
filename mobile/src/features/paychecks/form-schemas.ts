import { z } from 'zod';

import { parseMoneyToMinor } from '@/domain/money';

const isoDate = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Use YYYY-MM-DD.');
const money = z.string().superRefine((value, context) => {
  try {
    parseMoneyToMinor(value);
  } catch (error) {
    context.addIssue({
      code: 'custom',
      message: error instanceof Error ? error.message : 'Enter a valid amount.',
    });
  }
});

export const paycheckFormSchema = z.object({
  name: z.string().trim().min(1, 'Enter a paycheck name.').max(120),
  amount: money,
  incomeDate: isoDate,
  source: z.string().max(160),
  notes: z.string().max(2000),
});
export type PaycheckFormValues = z.infer<typeof paycheckFormSchema>;

export const entryFormSchema = z
  .object({
    entryType: z.enum(['BILL', 'SPENDING_BUCKET', 'SINKING_FUND']),
    name: z.string().trim().min(1, 'Enter an entry name.').max(160),
    amount: money,
    dueDate: z.string(),
    manualPay: z.boolean(),
    accountName: z.string().max(160),
    payee: z.string().max(160),
    notes: z.string().max(2000),
    paybackId: z.string(),
    target: z.string(),
    targetDate: z.string(),
  })
  .superRefine((values, context) => {
    if (
      values.entryType === 'BILL' &&
      values.dueDate &&
      !/^\d{4}-\d{2}-\d{2}$/.test(values.dueDate)
    ) {
      context.addIssue({ code: 'custom', path: ['dueDate'], message: 'Use YYYY-MM-DD.' });
    }
    if (values.entryType === 'SINKING_FUND' && values.target) {
      try {
        parseMoneyToMinor(values.target);
      } catch (error) {
        context.addIssue({
          code: 'custom',
          path: ['target'],
          message: error instanceof Error ? error.message : 'Enter a valid target.',
        });
      }
    }
    if (
      values.entryType === 'SINKING_FUND' &&
      values.targetDate &&
      !/^\d{4}-\d{2}-\d{2}$/.test(values.targetDate)
    ) {
      context.addIssue({ code: 'custom', path: ['targetDate'], message: 'Use YYYY-MM-DD.' });
    }
  });
export type EntryFormValues = z.infer<typeof entryFormSchema>;

export function today() {
  const now = new Date();
  const offset = now.getTimezoneOffset() * 60_000;
  return new Date(now.getTime() - offset).toISOString().slice(0, 10);
}
