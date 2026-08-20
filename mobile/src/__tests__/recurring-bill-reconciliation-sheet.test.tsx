import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import type { Entry, Paycheck, RecurringBill, RecurringBillOccurrence } from '@/api/contracts';
import { RecurringBillSection } from '@/features/recurring-bills/recurring-bill-reconciliation-sheet';

const mockPush = jest.fn();
const mockApi = {
  createRecurringBillFromEntry: jest.fn(),
  linkRecurringBill: jest.fn(),
  paycheck: jest.fn(),
  recurringBill: jest.fn(),
  recurringBills: jest.fn(),
  recurringBillTimeline: jest.fn(),
  unlinkRecurringBill: jest.fn(),
};

jest.mock('expo-router', () => ({ useRouter: () => ({ push: mockPush }) }));
jest.mock('@/api/use-yuuka-api', () => ({ useYuukaApi: () => mockApi }));
jest.mock('@/settings/settings-provider', () => ({
  useSettings: () => ({ settings: { currencyCode: 'USD' } }),
}));
jest.mock('@/theme/use-app-theme', () => ({
  useAppTheme: () => ({
    colors: {
      accent: '#00f',
      background: '#000',
      border: '#444',
      danger: '#f00',
      muted: '#999',
      surface: '#111',
      text: '#fff',
    },
  }),
}));

const queryClient = new QueryClient({
  defaultOptions: {
    mutations: { gcTime: Infinity, retry: false },
    queries: { gcTime: Infinity, retry: false },
  },
});

function wrapper(client: QueryClient) {
  return function Wrapper({ children }: PropsWithChildren) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

async function renderSection(currentEntry = entry(), currentPaycheck = paycheck(currentEntry)) {
  const onChanged = jest.fn().mockResolvedValue(undefined);
  const view = await render(
    <RecurringBillSection entry={currentEntry} onChanged={onChanged} paycheck={currentPaycheck} />,
    { wrapper: wrapper(queryClient) },
  );
  return { onChanged, view };
}

describe('RecurringBillSection', () => {
  afterEach(async () => {
    await cleanup();
  });

  beforeEach(() => {
    jest.resetAllMocks();
    queryClient.clear();
    mockApi.recurringBills.mockResolvedValue({ items: [definition()] });
    mockApi.recurringBillTimeline.mockResolvedValue({
      from: '2026-06-01',
      through: '2026-10-31',
      items: occurrences(),
    });
    mockApi.paycheck.mockResolvedValue(paycheck(entry()));
  });

  it('requires explicit occurrence selection when the Bill has no due date', async () => {
    const source = entry({ dueDate: null });
    const { view } = await renderSection(source, paycheck(source));

    fireEvent.press(view.getByLabelText('Link to recurring Bill'));
    fireEvent.press(await view.findByLabelText('Choose Netflix'));
    expect(
      await view.findByText('This Bill has no due date. Choose an occurrence month explicitly.'),
    ).toBeTruthy();
    expect(view.getByLabelText('Review changes').props.accessibilityState.disabled).toBe(true);
    fireEvent.press(view.getByLabelText('Choose August 21 occurrence'));
    await waitFor(() =>
      expect(view.getByLabelText('Review changes').props.accessibilityState.disabled).toBe(false),
    );
  });

  it('prefills editable definition values from the existing Bill snapshot', async () => {
    const source = entry();
    const { view } = await renderSection(source);

    fireEvent.press(view.getByLabelText('Turn into recurring Bill'));
    expect(await view.findByLabelText('Name')).toHaveProp('value', 'Netflix Subscription');
    expect(view.getByLabelText('Typical amount')).toHaveProp('value', '13.99');
    expect(view.getByLabelText('Monthly due day')).toHaveProp('value', '18');
  });

  it('shows a linked relationship and explains unlink snapshot preservation', async () => {
    const linked = entry({
      sourceRecurringBillDefinitionId: definition().id,
      sourceRecurringOccurrenceDate: '2026-08-21',
    });
    mockApi.recurringBill.mockResolvedValue(definition());
    mockApi.unlinkRecurringBill.mockResolvedValue(
      paycheck(
        {
          ...linked,
          sourceRecurringBillDefinitionId: null,
          sourceRecurringOccurrenceDate: null,
          version: 1,
        },
        { version: 4 },
      ),
    );
    const { view } = await renderSection(linked, paycheck(linked));

    expect(await view.findByText('Netflix')).toBeTruthy();
    expect(view.getByText('August 21 occurrence')).toBeTruthy();
    fireEvent.press(view.getByLabelText('View recurring Bill'));
    expect(mockPush).toHaveBeenCalledWith(`/recurring-bills/${definition().id}`);
    fireEvent.press(view.getByLabelText('Remove link'));
    expect(await view.findByText(/current name, amount/)).toBeTruthy();
    fireEvent.press(view.getByLabelText('Confirm remove link'));
    await waitFor(() =>
      expect(mockApi.unlinkRecurringBill).toHaveBeenCalledWith(linked.id, linked.version, 3),
    );
  });
});

function definition(): RecurringBill {
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
    version: 2,
  };
}

function occurrences(): RecurringBillOccurrence[] {
  return ['2026-06-21', '2026-07-21', '2026-08-21', '2026-09-21', '2026-10-21'].map(
    (occurrenceDate) => ({
      accountName: 'Visa',
      definitionId: definition().id,
      definitionVersion: 2,
      importCount: occurrenceDate === '2026-08-21' ? 1 : 0,
      imports:
        occurrenceDate === '2026-08-21'
          ? [
              {
                entryId: '44444444-4444-4444-8444-444444444444',
                paycheckId: '55555555-5555-4555-8555-555555555555',
                paycheckName: 'Utilities 1/2',
                status: 'PROCESSING' as const,
              },
            ]
          : [],
      name: 'Netflix',
      notes: 'Streaming',
      occurrenceDate,
      payee: 'Netflix Inc',
      paymentMethod: 'AUTOPAY' as const,
      typicalAmountMinor: 1499,
    }),
  );
}

function entry(overrides: Partial<Entry> = {}): Entry {
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
    ...overrides,
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
