/* eslint-disable @typescript-eslint/no-require-imports */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import type { Paycheck, RecurringBillOccurrence } from '@/api/contracts';
import { QuickAssignRecurringBillSheet } from '@/features/recurring-bills/quick-assign-recurring-bill-sheet';

const mockApi = { activePaychecks: jest.fn(), importRecurringBills: jest.fn() };
jest.mock('@/api/use-yuuka-api', () => ({ useYuukaApi: () => mockApi }));
jest.mock('@/settings/settings-provider', () => ({
  useSettings: () => ({ settings: { currencyCode: 'USD', theme: 'dark' } }),
}));
jest.mock('@/components/yuuka-mascot', () => {
  const React = require('react');
  const { Text } = require('react-native');
  return { YuukaMascot: () => React.createElement(Text, null, 'Yuuka') };
});

const occurrence: RecurringBillOccurrence = {
  accountName: 'Checking',
  definitionId: '11111111-1111-4111-8111-111111111111',
  definitionVersion: 4,
  importCount: 0,
  imports: [],
  name: 'Electric',
  notes: 'Budget account',
  occurrenceDate: '2026-08-09',
  payee: 'Power Co',
  paymentMethod: 'AUTOPAY',
  typicalAmountMinor: 12000,
};

function paycheck(
  id: string,
  name: string,
  incomeDate: string,
  unallocatedMinor: number,
): Paycheck {
  return {
    allocatedMinor: 50000 - unallocatedMinor,
    allocationPercent: 50,
    amountMinor: 50000,
    archivedAt: null,
    closedAt: null,
    completionPercent: 0,
    createdAt: '2026-08-01T12:00:00Z',
    entries: [],
    id,
    incomeDate,
    name,
    notPaidCount: 0,
    notPaidMinor: 0,
    notes: null,
    postedCount: 0,
    postedMinor: 0,
    processingCount: 0,
    processingMinor: 0,
    reopenedAt: null,
    requiresAttention: true,
    source: null,
    spendingBucketPerformance: null,
    state: 'ACTIVE',
    templateSourceId: null,
    unallocatedMinor,
    updatedAt: '2026-08-01T12:00:00Z',
    version: 3,
  };
}

const first = paycheck(
  '22222222-2222-4222-8222-222222222221',
  'First paycheck',
  '2026-08-01',
  20000,
);
const short = paycheck(
  '22222222-2222-4222-8222-222222222222',
  'Short paycheck',
  '2026-08-02',
  5000,
);

async function setup() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  });
  const Wrapper = ({ children }: PropsWithChildren) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  const onCreated = jest.fn();
  return {
    client,
    onCreated,
    view: await render(
      <QuickAssignRecurringBillSheet
        occurrence={occurrence}
        onClose={jest.fn()}
        onCreatePaycheck={jest.fn()}
        onCreated={onCreated}
      />,
      { wrapper: Wrapper },
    ),
  };
}

describe('quick recurring Bill assignment', () => {
  afterEach(async () => {
    await cleanup();
  });
  beforeEach(() => {
    jest.resetAllMocks();
    mockApi.activePaychecks.mockResolvedValue({
      hasNext: false,
      items: [first, short],
      page: 0,
      size: 100,
      totalItems: 2,
      totalPages: 1,
    });
  });

  it('shows no-paycheck creation state', async () => {
    mockApi.activePaychecks.mockResolvedValue({
      hasNext: false,
      items: [],
      page: 0,
      size: 100,
      totalItems: 0,
      totalPages: 0,
    });
    const { view } = await setup();
    expect(await view.findByText('No Active paychecks are available.')).toBeTruthy();
    expect(view.getByLabelText('Create Paycheck')).toBeTruthy();
  });

  it('preserves Active ordering and explains current, resulting, and insufficient amounts', async () => {
    const { view } = await setup();
    expect(await view.findByText('First paycheck')).toBeTruthy();
    expect(view.getAllByText(/Current:/)[0].props.children.join('')).toContain('$200.00');
    expect(view.getAllByText(/Current:/)[0].props.children.join('')).toContain('$80.00');
    expect(view.getByText('Short by $70.00')).toBeTruthy();
    expect(
      view.getByLabelText(/Short paycheck.*short by \$70.00/).props.accessibilityState.disabled,
    ).toBe(true);
  });

  it('imports one occurrence with typical amount and navigates by authoritative provenance', async () => {
    const createdEntryId = '33333333-3333-4333-8333-333333333333';
    mockApi.importRecurringBills.mockResolvedValue({
      ...first,
      entries: [
        {
          accountName: 'Checking',
          amountMinor: 12000,
          createdAt: '2026-08-05T12:00:00Z',
          dueDate: '2026-08-09',
          entryType: 'BILL',
          id: createdEntryId,
          name: 'Electric',
          notes: null,
          overBudget: null,
          paybackId: null,
          paycheckId: first.id,
          payee: 'Power Co',
          paymentMethod: 'AUTOPAY',
          position: 0,
          remainingMinor: null,
          sinkingFundId: null,
          sourceExpenseLedgerId: null,
          sourceRecurringBillDefinitionId: occurrence.definitionId,
          sourceRecurringOccurrenceDate: occurrence.occurrenceDate,
          spentMinor: null,
          status: 'NOT_PAID',
          targetDate: null,
          targetMinor: null,
          updatedAt: '2026-08-05T12:00:00Z',
          version: 0,
        },
      ],
    });
    const { onCreated, view } = await setup();
    await fireEvent.press(await view.findByLabelText(/First paycheck/));
    await fireEvent.press(view.getByLabelText('Confirm import'));
    await waitFor(() =>
      expect(mockApi.importRecurringBills).toHaveBeenCalledWith(first.id, 3, [
        {
          amountMinor: 12000,
          definitionId: occurrence.definitionId,
          definitionVersion: 4,
          occurrenceDate: '2026-08-09',
          updateTypicalAmount: false,
        },
      ]),
    );
    expect(onCreated).toHaveBeenCalledWith(first.id, createdEntryId);
    await waitFor(() =>
      expect(view.getByLabelText('Confirm import').props.accessibilityState.busy).toBe(false),
    );
  });

  it('prevents duplicate submission and preserves selection and override after failure', async () => {
    let rejectImport: (error: Error) => void = () => undefined;
    mockApi.importRecurringBills.mockReturnValue(
      new Promise((_resolve, reject) => {
        rejectImport = reject;
      }),
    );
    const { view } = await setup();
    await fireEvent.press(await view.findByLabelText('Edit amount'));
    await fireEvent.changeText(view.getByLabelText('Amount for this paycheck'), '130.00');
    await fireEvent.press(view.getByLabelText('Update typical amount'));
    await fireEvent.press(view.getByLabelText(/First paycheck/));
    fireEvent.press(view.getByLabelText('Confirm import'));
    fireEvent.press(view.getByLabelText('Confirm import'));
    expect(mockApi.importRecurringBills).toHaveBeenCalledTimes(1);
    await act(async () => {
      rejectImport(new Error('Paycheck changed. Refresh and try again.'));
    });
    expect(await view.findByText('Paycheck changed. Refresh and try again.')).toBeTruthy();
    await waitFor(() =>
      expect(view.getByLabelText('Confirm import').props.accessibilityState.busy).toBe(false),
    );
    expect(view.getByLabelText(/First paycheck/).props.accessibilityState.checked).toBe(true);
    expect(view.getByText('$130.00')).toBeTruthy();
  });
});
