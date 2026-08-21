import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import type { Entry, Payback, Paycheck } from '@/api/contracts';
import { EntryEditor } from '@/features/paychecks/entry-editor';

const mockApi = {
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

describe('EntryEditor recurring Bill dirty-form protection', () => {
  afterEach(() => cleanup());
  beforeEach(() => {
    Object.values(mockApi).forEach((mock) => mock.mockReset());
    mockApi.recurringBills.mockResolvedValue({ items: [] });
    mockApi.recurringBillTimeline.mockResolvedValue({ from: '', items: [], through: '' });
  });

  it.each([
    [
      'name',
      async (view: Awaited<ReturnType<typeof renderEditor>>) => {
        await fireEvent.changeText(view.getByLabelText('Name'), 'Unsaved name');
      },
    ],
    [
      'amount',
      async (view: Awaited<ReturnType<typeof renderEditor>>) => {
        await fireEvent.changeText(view.getByLabelText('Amount'), '14.00');
      },
    ],
    [
      'notes',
      async (view: Awaited<ReturnType<typeof renderEditor>>) => {
        await fireEvent.changeText(view.getByLabelText('Notes (optional)'), 'Unsaved notes');
      },
    ],
    [
      'payment method',
      async (view: Awaited<ReturnType<typeof renderEditor>>) => {
        await fireEvent.press(view.getByLabelText('I need to pay this manually'));
      },
    ],
  ])('disables recurring actions after an unsaved %s change', async (_field, change) => {
    const view = await renderEditor();

    await change(view);

    await waitFor(() => {
      expect(view.getByText(/Save or discard your entry changes/)).toBeTruthy();
      expect(view.getByLabelText('Link to recurring Bill').props.accessibilityState.disabled).toBe(
        true,
      );
      expect(
        view.getByLabelText('Turn into recurring Bill').props.accessibilityState.disabled,
      ).toBe(true);
    });
  });

  it('disables recurring actions after an unsaved Payback selection', async () => {
    const view = await renderEditor({ paybacks: [payback()] });

    await fireEvent.press(view.getByLabelText('Apply to Payback, selected No Payback'));
    await fireEvent.press(await view.findByText('Car repair'));

    await waitFor(() => {
      expect(view.getByText(/Save or discard your entry changes/)).toBeTruthy();
      expect(view.getByLabelText('Link to recurring Bill').props.accessibilityState.disabled).toBe(
        true,
      );
    });
  });

  it('guards a disabled recurring handler even when invoked directly', async () => {
    const view = await renderEditor();
    await fireEvent.changeText(view.getByLabelText('Name'), 'Unsaved name');
    const link = view.getByLabelText('Link to recurring Bill');

    await fireEvent.press(link);

    expect(mockApi.recurringBills).not.toHaveBeenCalled();
    expect(view.queryByText('Choose recurring Bill')).toBeNull();
  });

  it('does not expose reconciliation when only the unsaved form changes a non-Bill to Bill', async () => {
    const view = await renderEditor({ source: entry({ entryType: 'SPENDING_BUCKET' }) });

    await fireEvent.press(view.getByTestId('segmented-Entry type-BILL'));

    await waitFor(() => {
      expect(view.getByTestId('segmented-Entry type-BILL').props.accessibilityState.checked).toBe(
        true,
      );
      expect(view.queryByLabelText('Link to recurring Bill')).toBeNull();
      expect(view.queryByLabelText('Turn into recurring Bill')).toBeNull();
    });
  });

  it('keeps a persisted Bill relationship visible but unusable after an unsaved type change', async () => {
    const view = await renderEditor();

    await fireEvent.press(view.getByTestId('segmented-Entry type-SPENDING_BUCKET'));

    await waitFor(() => {
      expect(view.getByText('Recurring Bill')).toBeTruthy();
      expect(view.getByLabelText('Link to recurring Bill').props.accessibilityState.disabled).toBe(
        true,
      );
    });
  });

  it('allows a clean persisted Bill to start the link workflow', async () => {
    const linkView = await renderEditor();
    await fireEvent.press(await linkView.findByLabelText('Link to recurring Bill'));
    await waitFor(() => expect(mockApi.recurringBills).toHaveBeenCalled());
    expect(linkView.getByText('Choose recurring Bill')).toBeTruthy();
  });

  it('allows a clean persisted Bill to start the create workflow', async () => {
    const createView = await renderEditor();
    await fireEvent.press(await createView.findByLabelText('Turn into recurring Bill'));
    expect(createView.getAllByText('Turn into recurring Bill')).toHaveLength(2);
    expect(createView.getByTestId('recurring-reconciliation-modal')).toBeTruthy();
  });

  it('allows a clean persisted linked Bill to start the unlink workflow', async () => {
    const linked = entry({
      sourceRecurringBillDefinitionId: '33333333-3333-4333-8333-333333333333',
      sourceRecurringOccurrenceDate: '2026-08-21',
    });
    mockApi.recurringBill.mockResolvedValue({ id: linked.sourceRecurringBillDefinitionId });
    const unlinkView = await renderEditor({ source: linked });
    await fireEvent.press(await unlinkView.findByLabelText('Remove link'));
    expect(unlinkView.getByText(/Remove the recurring relationship/)).toBeTruthy();
  });
});

async function renderEditor({
  paybacks = [],
  source = entry(),
}: { paybacks?: Payback[]; source?: Entry } = {}) {
  const currentPaycheck = paycheck(source);
  const client = new QueryClient({
    defaultOptions: {
      mutations: { gcTime: Infinity, retry: false },
      queries: { gcTime: Infinity, retry: false },
    },
  });
  return render(
    <EntryEditor
      entry={source}
      onClose={jest.fn()}
      onRecurringChanged={jest.fn().mockResolvedValue(undefined)}
      onSubmit={jest.fn()}
      paybacks={paybacks}
      paycheck={currentPaycheck}
      visible
    />,
    {
      wrapper: ({ children }: PropsWithChildren) => (
        <QueryClientProvider client={client}>{children}</QueryClientProvider>
      ),
    },
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
    paymentMethod: 'AUTOPAY',
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

function paycheck(currentEntry: Entry): Paycheck {
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
  };
}

function payback(): Payback {
  return {
    borrowedDate: '2026-07-12',
    createdAt: '2026-07-12T12:00:00Z',
    id: '44444444-4444-4444-8444-444444444444',
    name: 'Car repair',
    notes: null,
    openingRemainingAmountMinor: 10000,
    originalAmountMinor: 10000,
    position: 0,
    progressPercent: 0,
    remainingMinor: 10000,
    repaidMinor: 0,
    repaymentCount: 0,
    source: null,
    state: 'ACTIVE',
    updatedAt: '2026-07-12T12:30:00Z',
    version: 0,
  };
}
