import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import type { Entry, Paycheck, RecurringBill, RecurringBillOccurrence } from '@/api/contracts';
import { RecurringBillSection } from '@/features/recurring-bills/recurring-bill-reconciliation-sheet';

const mockApi = {
  linkRecurringBill: jest.fn(),
  paycheck: jest.fn(),
  recurringBill: jest.fn(),
  recurringBills: jest.fn(),
  recurringBillTimeline: jest.fn(),
};

jest.mock('expo-router', () => ({ useRouter: () => ({ push: jest.fn() }) }));
jest.mock('@/api/use-yuuka-api', () => ({ useYuukaApi: () => mockApi }));
jest.mock('@/settings/settings-provider', () => ({
  useSettings: () => ({ settings: { currencyCode: 'USD' } }),
}));
jest.mock('@/theme/use-app-theme', () => ({
  useAppTheme: () => ({
    colors: {
      accent: '#00f',
      accentText: '#fff',
      background: '#000',
      border: '#444',
      danger: '#f00',
      dangerSoft: '#300',
      muted: '#999',
      surface: '#111',
      surfaceElevated: '#222',
      text: '#fff',
    },
  }),
}));

describe('recurring Bill reconciliation recovery', () => {
  afterEach(() => cleanup());
  beforeEach(() => jest.resetAllMocks());

  it('reviews the same occurrence snapshot and version that it submits', async () => {
    const source = entry();
    const currentPaycheck = paycheck(source);
    const timelineOccurrence = {
      ...occurrence(3),
      name: 'Netflix Plus',
      typicalAmountMinor: 1599,
    };
    const updatedPaycheck = paycheck(
      {
        ...source,
        amountMinor: 1599,
        name: 'Netflix Plus',
        sourceRecurringBillDefinitionId: timelineOccurrence.definitionId,
        sourceRecurringOccurrenceDate: timelineOccurrence.occurrenceDate,
        version: 1,
      },
      { version: 4 },
    );
    mockApi.recurringBills.mockResolvedValue({ items: [definition(2)] });
    mockApi.recurringBillTimeline.mockResolvedValue(timeline(timelineOccurrence));
    mockApi.linkRecurringBill.mockResolvedValue(updatedPaycheck);
    const onChanged = jest.fn().mockResolvedValue(undefined);
    const view = await renderSection(source, currentPaycheck, onChanged);

    fireEvent.press(view.getByLabelText('Link to recurring Bill'));
    fireEvent.press(await view.findByLabelText('Choose Netflix'));
    fireEvent.press(await view.findByLabelText('Review changes'));

    expect(await view.findByText('Link to Netflix Plus')).toBeTruthy();
    expect(view.getByText('$13.99 → $15.99')).toBeTruthy();
    fireEvent.press(view.getByLabelText('Confirm recurring link'));

    await waitFor(() =>
      expect(mockApi.linkRecurringBill).toHaveBeenCalledWith(
        source.id,
        expect.objectContaining({ definitionVersion: 3 }),
      ),
    );
    await waitFor(() => expect(onChanged).toHaveBeenCalledWith(updatedPaycheck));
    await waitFor(() => expect(view.getByLabelText('Link to recurring Bill')).toBeTruthy());
  });

  it('refreshes an auto-suggested occurrence after a stale failure and prevents duplicate submits', async () => {
    const queryClient = new QueryClient({
      defaultOptions: {
        mutations: { gcTime: Infinity, retry: false },
        queries: { gcTime: Infinity, retry: false },
      },
    });
    const source = entry();
    const currentPaycheck = paycheck(source);
    const refreshedDefinition = definition(3);
    const refreshedOccurrence = occurrence(3);
    const updatedEntry: Entry = {
      ...source,
      amountMinor: 1499,
      dueDate: '2026-08-21',
      name: 'Netflix',
      paymentMethod: 'AUTOPAY',
      sourceRecurringBillDefinitionId: refreshedDefinition.id,
      sourceRecurringOccurrenceDate: refreshedOccurrence.occurrenceDate,
      version: 1,
    };
    const updatedPaycheck = paycheck(updatedEntry, { version: 4 });
    const onChanged = jest.fn().mockResolvedValue(undefined);
    mockApi.recurringBills
      .mockResolvedValueOnce({ items: [definition(2)] })
      .mockResolvedValue({ items: [refreshedDefinition] });
    mockApi.recurringBillTimeline
      .mockResolvedValueOnce(timeline(occurrence(2)))
      .mockResolvedValue(timeline(refreshedOccurrence));
    mockApi.paycheck.mockResolvedValue(currentPaycheck);
    mockApi.linkRecurringBill
      .mockRejectedValueOnce(new Error('The recurring Bill changed.'))
      .mockResolvedValue(updatedPaycheck);

    const view = await renderSection(source, currentPaycheck, onChanged, queryClient);

    fireEvent.press(view.getByLabelText('Link to recurring Bill'));
    fireEvent.press(await view.findByLabelText('Choose Netflix'));
    expect(await view.findByLabelText('Selected August 21 occurrence')).toBeTruthy();
    fireEvent.press(view.getByLabelText('Review changes'));
    fireEvent.press(await view.findByLabelText('Confirm recurring link'));
    fireEvent.press(view.getByLabelText('Confirm recurring link'));

    await waitFor(() => expect(mockApi.linkRecurringBill).toHaveBeenCalledTimes(1));
    expect(mockApi.linkRecurringBill).toHaveBeenLastCalledWith(
      source.id,
      expect.objectContaining({ definitionVersion: 2 }),
    );
    expect(await view.findByText('The recurring Bill changed.')).toBeTruthy();
    await waitFor(() => expect(mockApi.recurringBillTimeline).toHaveBeenCalledTimes(2));

    fireEvent.press(view.getByLabelText('Confirm recurring link'));

    await waitFor(() => expect(mockApi.linkRecurringBill).toHaveBeenCalledTimes(2));
    expect(mockApi.linkRecurringBill).toHaveBeenLastCalledWith(
      source.id,
      expect.objectContaining({ definitionVersion: 3 }),
    );
    await waitFor(() => expect(onChanged).toHaveBeenCalledWith(updatedPaycheck));
    await waitFor(() => expect(view.getByLabelText('Link to recurring Bill')).toBeTruthy());
  });
});

async function renderSection(
  source: Entry,
  currentPaycheck: Paycheck,
  onChanged: jest.Mock,
  queryClient = new QueryClient({
    defaultOptions: {
      mutations: { gcTime: Infinity, retry: false },
      queries: { gcTime: Infinity, retry: false },
    },
  }),
) {
  return render(
    <RecurringBillSection entry={source} onChanged={onChanged} paycheck={currentPaycheck} />,
    {
      wrapper: ({ children }: PropsWithChildren) => (
        <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
      ),
    },
  );
}

function definition(version: number): RecurringBill {
  return {
    accountName: 'Visa',
    active: true,
    createdAt: '2026-08-01T12:00:00Z',
    dueDay: 21,
    id: '33333333-3333-4333-8333-333333333333',
    name: 'Netflix',
    notes: 'Streaming',
    payee: 'Netflix Inc',
    paymentMethod: 'AUTOPAY',
    recurrenceType: 'MONTHLY',
    typicalAmountMinor: 1499,
    updatedAt: '2026-08-01T12:00:00Z',
    version,
  };
}

function occurrence(definitionVersion: number): RecurringBillOccurrence {
  return {
    accountName: 'Visa',
    definitionId: definition(definitionVersion).id,
    definitionVersion,
    importCount: 0,
    imports: [],
    name: 'Netflix',
    notes: 'Streaming',
    occurrenceDate: '2026-08-21',
    payee: 'Netflix Inc',
    paymentMethod: 'AUTOPAY',
    typicalAmountMinor: 1499,
  };
}

function timeline(item: RecurringBillOccurrence) {
  return { from: '2026-06-01', items: [item], through: '2026-10-31' };
}

function entry(): Entry {
  return {
    accountName: 'Visa',
    amountMinor: 1399,
    createdAt: '2026-08-01T12:00:00Z',
    dueDate: '2026-08-18',
    entryType: 'BILL',
    id: '11111111-1111-4111-8111-111111111111',
    name: 'Netflix Subscription',
    notes: 'Streaming',
    overBudget: null,
    paybackId: null,
    payee: 'Netflix Inc',
    paycheckId: '22222222-2222-4222-8222-222222222222',
    paymentMethod: 'MANUAL',
    position: 0,
    remainingMinor: null,
    sinkingFundId: null,
    sourceExpenseLedgerId: null,
    sourceRecurringBillDefinitionId: null,
    sourceRecurringOccurrenceDate: null,
    spentMinor: null,
    status: 'NOT_PAID',
    targetDate: null,
    targetMinor: null,
    updatedAt: '2026-08-01T12:00:00Z',
    version: 0,
  };
}

function paycheck(currentEntry: Entry, overrides: Partial<Paycheck> = {}): Paycheck {
  return {
    allocatedMinor: currentEntry.amountMinor,
    allocationPercent: 6.995,
    amountMinor: 20000,
    archivedAt: null,
    closedAt: null,
    completionPercent: 0,
    createdAt: '2026-08-01T12:00:00Z',
    entries: [currentEntry],
    id: currentEntry.paycheckId,
    incomeDate: '2026-08-15',
    name: 'Utilities 2/2',
    notPaidCount: 1,
    notPaidMinor: currentEntry.amountMinor,
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
    unallocatedMinor: 20000 - currentEntry.amountMinor,
    updatedAt: '2026-08-01T12:00:00Z',
    version: 3,
    ...overrides,
  };
}
