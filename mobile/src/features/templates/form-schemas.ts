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

export const templateFormSchema = z.object({
  name: z.string().trim().min(1, 'Enter a template name.').max(120),
  description: z.string().max(1000),
});
export type TemplateFormValues = z.infer<typeof templateFormSchema>;

export const templateEntryFormSchema = z
  .object({
    entryType: z.enum(['BILL', 'SPENDING_BUCKET', 'SINKING_FUND']),
    name: z.string().trim().min(1, 'Enter an entry name.').max(160),
    amount: money,
    defaultDueOffsetDays: z.string(),
    manualPay: z.boolean(),
    accountName: z.string().max(160),
    payee: z.string().max(160),
    notes: z.string().max(2000),
    target: z.string(),
    targetDate: z.string(),
  })
  .superRefine((values, context) => {
    if (
      values.entryType === 'BILL' &&
      values.defaultDueOffsetDays &&
      !/^-?\d+$/.test(values.defaultDueOffsetDays)
    ) {
      context.addIssue({
        code: 'custom',
        path: ['defaultDueOffsetDays'],
        message: 'Enter whole days.',
      });
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
    if (values.entryType === 'SINKING_FUND' && values.targetDate) {
      const result = isoDate.safeParse(values.targetDate);
      if (!result.success) {
        context.addIssue({ code: 'custom', path: ['targetDate'], message: 'Use YYYY-MM-DD.' });
      }
    }
  });
export type TemplateEntryFormValues = z.infer<typeof templateEntryFormSchema>;
