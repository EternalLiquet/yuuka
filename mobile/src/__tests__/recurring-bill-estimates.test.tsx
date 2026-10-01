import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
import { useState, type PropsWithChildren } from 'react';

import type { Entry, Paycheck, RecurringBillOccurrence } from '@/api/contracts';
import { ActualBillSheet } from '@/features/recurring-bills/actual-bill-sheet';
import { DuplicateRecurringBill } from '@/features/recurring-bills/duplicate-recurring-bill';
import { recurringImportSelection } from '@/features/recurring-bills/import-recurring-bills-sheet';
import type { TemplateApplicationDraftEntry } from '@/features/templates/application-draft';

const mockApi = {
  recurringBillTimeline: jest.fn(),
  recurringBillActualContext: jest.fn(),
  recordRecurringBillActual: jest.fn(),
};
jest.mock('@/api/use-yuuka-api', () => ({ useYuukaApi: () => mockApi }));
jest.mock('@/settings/settings-provider', () => ({
  useSettings: () => ({ settings: { currencyCode: 'USD' } }),
}));
jest.mock('@/theme/use-app-theme', () => ({
  useAppTheme: () => ({
    colors: { background: '#fff', text: '#000', surface: '#fff', border: '#888' },
  }),
}));

const client = new QueryClient({
  defaultOptions: { queries: { retry: false, gcTime: Infinity }, mutations: { retry: false } },
});
function wrapper({ children }: PropsWithChildren) {
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}
const entry: Entry = {
  id: '11111111-1111-4111-8111-111111111111',
  paycheckId: '11111111-1111-4111-8111-111111111112',
  name: 'Power',
  entryType: 'BILL',
  paymentMethod: 'AUTOPAY',
  amountMinor: 15000,
  amountEstimated: true,
  paybackId: null,
  status: 'NOT_PAID',
  position: 0,
  dueDate: '2028-02-29',
  accountName: null,
  payee: null,
  notes: null,
  targetMinor: null,
  targetDate: null,
  sourceRecurringBillDefinitionId: '11111111-1111-4111-8111-111111111113',
  sourceRecurringOccurrenceDate: '2028-02-29',
  spentMinor: null,
  remainingMinor: null,
  overBudget: null,
  createdAt: '2028-02-01T00:00:00Z',
  updatedAt: '2028-02-01T00:00:00Z',
  version: 3,
};
const paycheck: Paycheck = {
  id: entry.paycheckId,
  name: 'February bills',
  amountMinor: 20000,
  incomeDate: '2028-02-01',
  source: null,
  state: 'ACTIVE',
  templateSourceId: null,
  notes: null,
  allocatedMinor: 15000,
  unallocatedMinor: 5000,
  allocationPercent: 75,
  postedMinor: 0,
  processingMinor: 0,
  notPaidMinor: 15000,
  completionPercent: 0,
  postedCount: 0,
  processingCount: 0,
  notPaidCount: 1,
  requiresAttention: true,
  entries: [entry],
  createdAt: entry.createdAt,
  updatedAt: entry.updatedAt,
  closedAt: null,
  reopenedAt: null,
  archivedAt: null,
  version: 5,
  spendingBucketPerformance: { budgetedMinor: 0, spentMinor: 0, netMinor: 0 },
};
const occurrence: RecurringBillOccurrence = {
  definitionId: entry.sourceRecurringBillDefinitionId!,
  definitionVersion: 2,
  occurrenceDate: '2028-02-29',
  name: 'Power',
  amountMode: 'VARIABLE',
  amountMinor: null,
  amountEntered: false,
  typicalAmountMinor: 99999,
  planningAmountMinor: 15000,
  occurrenceAmountVersion: null,
  paymentMethod: 'AUTOPAY',
  accountName: null,
  payee: null,
  notes: null,
  importCount: 0,
  imports: [],
};
const draft: TemplateApplicationDraftEntry = {
  accountName: null,
  amountMinor: 0,
  clientId: 'draft',
  defaultDueOffsetDays: null,
  entryType: 'BILL',
  name: 'Power',
  notes: 'Keep me',
  payee: null,
  paymentMethod: 'MANUAL',
  sourceRecurringBillDefinitionId: occurrence.definitionId,
  sourceRecurringOccurrenceDate: null,
  targetDate: null,
  targetMinor: null,
  sourceIncomeDate: '2028-01-01',
  sourceBillDate: '2028-01-31',
};
const onChange = jest.fn();
function Draft({ date = '2028-02-01' }: { date?: string }) {
  const [value, setValue] = useState(draft);
  return (
    <DuplicateRecurringBill
      entry={value}
      incomeDate={date}
      duplicateInDraft={() => false}
      onChange={(next) => {
        setValue(next);
        onChange(next);
      }}
    />
  );
}
beforeEach(() => {
  jest.clearAllMocks();
  client.clear();
  mockApi.recurringBillTimeline.mockResolvedValue({ items: [occurrence] });
  mockApi.recurringBillActualContext.mockResolvedValue({
    definitionId: occurrence.definitionId,
    definitionVersion: 2,
    occurrenceDate: occurrence.occurrenceDate,
    occurrenceAmountVersion: null,
    actualAmountMinor: null,
    definitionReviewRequired: false,
  });
  mockApi.recordRecurringBillActual.mockResolvedValue({ ...paycheck, unallocatedMinor: 8000 });
});
afterEach(async () => {
  await cleanup();
  client.clear();
});

it('keeps estimates separate from dormant fixed amounts and gives actual zero priority', () => {
  expect(recurringImportSelection(occurrence)).toMatchObject({
    amountMinor: 15000,
    amountEstimated: true,
  });
  expect(
    recurringImportSelection({ ...occurrence, amountMinor: 0, amountEntered: true }),
  ).toMatchObject({ amountMinor: 0, amountEstimated: false });
  expect(() => recurringImportSelection({ ...occurrence, planningAmountMinor: null })).toThrow(
    'Enter an amount',
  );
});
it('reviews the backend-provided clamped date and resets review after income date changes', async () => {
  const view = await render(<Draft />, { wrapper });
  await fireEvent.press(await view.findByLabelText('Choose 2028-02-29 (suggested)'));
  expect(onChange).toHaveBeenLastCalledWith(
    expect.objectContaining({
      amountMinor: 15000,
      amountEstimated: true,
      sourceRecurringOccurrenceDate: '2028-02-29',
      reviewedIncomeDate: '2028-02-01',
      notes: 'Keep me',
      paymentMethod: 'MANUAL',
    }),
  );
  await view.rerender(<Draft date="2028-02-08" />);
  expect(view.queryByLabelText('Selected 2028-02-29 (suggested)')).toBeNull();
});
it('requires a typed estimate when neither actual nor estimate exists', async () => {
  mockApi.recurringBillTimeline.mockResolvedValue({
    items: [{ ...occurrence, planningAmountMinor: null }],
  });
  const view = await render(<Draft />, { wrapper });
  await fireEvent.press(await view.findByLabelText('Choose 2028-02-29 (suggested)'));
  expect(onChange.mock.lastCall?.[0].reviewedIncomeDate).toBeUndefined();
  await fireEvent.changeText(view.getByLabelText('Estimated amount for Power'), '42.25');
  await fireEvent.press(view.getByLabelText('Use this estimate'));
  expect(onChange).toHaveBeenLastCalledWith(
    expect.objectContaining({
      amountMinor: 4225,
      amountEstimated: true,
      reviewedIncomeDate: '2028-02-01',
    }),
  );
});
it('asks explicitly before another copy of an already assigned bill', async () => {
  mockApi.recurringBillTimeline.mockResolvedValue({ items: [{ ...occurrence, importCount: 1 }] });
  const view = await render(<Draft />, { wrapper });
  await fireEvent.press(await view.findByLabelText('Choose 2028-02-29 (suggested)'));
  expect(onChange.mock.lastCall?.[0].confirmDuplicateOccurrence).toBe(false);
  await fireEvent.press(view.getByLabelText('Add another copy of this bill'));
  expect(onChange.mock.lastCall?.[0].confirmDuplicateOccurrence).toBe(true);
});
it('records actual on only the selected bill and reports returned money after success', async () => {
  const onChanged = jest.fn().mockResolvedValue(undefined);
  const view = await render(
    <ActualBillSheet entry={entry} paycheck={paycheck} onClose={jest.fn()} onChanged={onChanged} />,
    { wrapper },
  );
  await waitFor(() =>
    expect(view.getByLabelText('Save actual bill').props.accessibilityState.disabled).toBe(false),
  );
  await fireEvent.changeText(view.getByLabelText('Actual bill amount'), '120');
  await fireEvent.press(view.getByLabelText('Save actual bill'));
  expect(mockApi.recordRecurringBillActual).toHaveBeenCalledWith(
    entry.id,
    expect.objectContaining({
      amountMinor: 12000,
      entryVersion: 3,
      paycheckVersion: 5,
      definitionVersion: 2,
      occurrenceDate: '2028-02-29',
    }),
  );
  expect(await view.findByText('$30.00 returned to Unallocated.')).toBeTruthy();
  expect(onChanged).toHaveBeenCalledTimes(1);
  expect(onChanged).toHaveBeenCalledWith(
    expect.objectContaining({ id: paycheck.id, unallocatedMinor: 8000 }),
  );
});
it('keeps the typed actual after a failed save and requires review when definition changed', async () => {
  mockApi.recurringBillActualContext.mockResolvedValue({
    definitionId: occurrence.definitionId,
    definitionVersion: 4,
    occurrenceDate: occurrence.occurrenceDate,
    occurrenceAmountVersion: null,
    actualAmountMinor: null,
    definitionReviewRequired: true,
  });
  mockApi.recordRecurringBillActual.mockRejectedValue(
    new Error('There is not enough money left in this paycheck.'),
  );
  const view = await render(
    <ActualBillSheet entry={entry} paycheck={paycheck} onClose={jest.fn()} onChanged={jest.fn()} />,
    { wrapper },
  );
  await view.findByLabelText('This is the bill I want to update');
  expect(view.getByLabelText('Save actual bill').props.accessibilityState.disabled).toBe(true);
  await fireEvent.press(view.getByLabelText('This is the bill I want to update'));
  await fireEvent.changeText(view.getByLabelText('Actual bill amount'), '220');
  await fireEvent.press(view.getByLabelText('Save actual bill'));
  expect(await view.findByText('There is not enough money left in this paycheck.')).toBeTruthy();
  expect(view.getByLabelText('Actual bill amount').props.value).toBe('220');
  expect(view.queryByLabelText('Done')).toBeNull();
});

it('refreshes a stale chosen bill and uses the newly saved actual instead of its estimate', async () => {
  const view = await render(<Draft />, { wrapper });
  await fireEvent.press(await view.findByLabelText('Choose 2028-02-29 (suggested)'));
  mockApi.recurringBillTimeline.mockResolvedValue({
    items: [
      {
        ...occurrence,
        definitionVersion: 3,
        amountMinor: 12000,
        amountEntered: true,
        occurrenceAmountVersion: 0,
      },
    ],
  });
  await fireEvent.press(view.getByLabelText('Refresh bill dates'));
  await waitFor(() =>
    expect(
      view.getByLabelText('Choose 2028-02-29 (suggested)').props.accessibilityState.disabled,
    ).toBe(false),
  );
  await fireEvent.press(view.getByLabelText('Choose 2028-02-29 (suggested)'));
  expect(onChange).toHaveBeenLastCalledWith(
    expect.objectContaining({
      amountMinor: 12000,
      amountEstimated: false,
      recurringDefinitionVersion: 3,
      occurrenceAmountVersion: 0,
    }),
  );
});
