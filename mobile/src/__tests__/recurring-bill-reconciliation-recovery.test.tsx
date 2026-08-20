import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, userEvent, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import type { Entry, Paycheck, RecurringBill, RecurringBillOccurrence } from '@/api/contracts';
import { RecurringBillSection } from '@/features/recurring-bills/recurring-bill-reconciliation-sheet';

const mockApi = {
  createRecurringBillFromEntry: jest.fn(),
  linkRecurringBill: jest.fn(),
  paycheck: jest.fn(),
  recurringBill: jest.fn(),
  recurringBills: jest.fn(),
  recurringBillTimeline: jest.fn(),
  unlinkRecurringBill: jest.fn(),
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
  beforeEach(() => Object.values(mockApi).forEach((mock) => mock.mockReset()));

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

    await fireEvent.press(view.getByLabelText('Link to recurring Bill'));
    await fireEvent.press(await view.findByLabelText('Choose Netflix'));
    await fireEvent.press(await view.findByLabelText('Selected August 21 occurrence'));
    await fireEvent.press(view.getByText('Review changes'));

    expect(await view.findByText('Link to Netflix Plus')).toBeTruthy();
    expect(view.getByText('$13.99 → $15.99')).toBeTruthy();
    await fireEvent.press(view.getByLabelText('Confirm recurring link'));

    await waitFor(() =>
      expect(mockApi.linkRecurringBill).toHaveBeenCalledWith(
        source.id,
        expect.objectContaining({ definitionVersion: 3 }),
      ),
    );
    await waitFor(() => expect(onChanged).toHaveBeenCalledWith(updatedPaycheck));
    await waitFor(() => expect(view.getByLabelText('Link to recurring Bill')).toBeTruthy());
    await fireEvent.press(view.getByLabelText('Link to recurring Bill'));
    expect(await view.findByText('Choose recurring Bill')).toBeTruthy();
    expect(view.queryByLabelText('Confirm recurring link')).toBeNull();
  });

  it('refreshes an auto-suggested occurrence after a stale failure', async () => {
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
    await fireEvent.press(view.getByLabelText('Review changes'));
    await fireEvent.press(await view.findByLabelText('Confirm recurring link'));

    await waitFor(() => expect(mockApi.linkRecurringBill).toHaveBeenCalledTimes(1));
    expect(mockApi.linkRecurringBill).toHaveBeenLastCalledWith(
      source.id,
      expect.objectContaining({ definitionVersion: 2 }),
    );
    expect(await view.findByText('The recurring Bill changed.')).toBeTruthy();
    await waitFor(() => expect(mockApi.recurringBillTimeline).toHaveBeenCalledTimes(2));

    await waitFor(() => expect(view.getByLabelText('Confirm recurring link')).toBeTruthy());
    await fireEvent.press(view.getByLabelText('Confirm recurring link'));

    await waitFor(() => expect(mockApi.linkRecurringBill).toHaveBeenCalledTimes(2));
    expect(mockApi.linkRecurringBill).toHaveBeenLastCalledWith(
      source.id,
      expect.objectContaining({ definitionVersion: 3 }),
    );
    await waitFor(() => expect(onChanged).toHaveBeenCalledWith(updatedPaycheck));
    await waitFor(() => expect(view.getByLabelText('Link to recurring Bill')).toBeTruthy());
  });

  it('recognizes a lost link response without repeating the write', async () => {
    await expectLostResponse('link');
  });

  it('recognizes a lost unlink response without repeating the write', async () => {
    await expectLostResponse('unlink');
  });

  it('recognizes a lost create response without repeating the write', async () => {
    await expectLostResponse('create');
  });

  it('blocks dismissal and writes while an outcome is unknown, then retries with refreshed versions', async () => {
    const source = entry();
    const currentPaycheck = paycheck(source);
    const refreshed = paycheck({ ...source, version: 7 }, { version: 11 });
    const updated = paycheck(
      {
        ...source,
        sourceRecurringBillDefinitionId: definition(3).id,
        sourceRecurringOccurrenceDate: occurrence(3).occurrenceDate,
        version: 8,
      },
      { version: 12 },
    );
    mockApi.recurringBills.mockResolvedValue({ items: [definition(3)] });
    mockApi.recurringBillTimeline.mockResolvedValue(timeline(occurrence(3)));
    mockApi.linkRecurringBill
      .mockRejectedValueOnce(new Error('Network failed.'))
      .mockResolvedValueOnce(updated);
    mockApi.paycheck
      .mockRejectedValueOnce(new Error('Refresh failed.'))
      .mockResolvedValue(refreshed);
    const onChanged = jest.fn().mockResolvedValue(undefined);
    const view = await renderSection(source, currentPaycheck, onChanged);
    await openLinkReview(view);
    await fireEvent.press(view.getByLabelText('Confirm recurring link'));

    expect(await view.findByText(/could not confirm whether/)).toBeTruthy();
    expect(view.getByLabelText('Confirm recurring link').props.accessibilityState.disabled).toBe(
      true,
    );
    expect(
      view.getByLabelText('Close recurring Bill workflow').props.accessibilityState.disabled,
    ).toBe(true);
    await fireEvent.press(view.getByLabelText('Close recurring Bill workflow'));
    expect(view.getByTestId('recurring-reconciliation-modal')).toBeTruthy();
    view.getByTestId('recurring-reconciliation-modal').props.onRequestClose();
    expect(view.getByTestId('recurring-reconciliation-modal')).toBeTruthy();
    expect(mockApi.linkRecurringBill).toHaveBeenCalledTimes(1);

    await fireEvent.press(view.getByLabelText('Check result'));
    await waitFor(() =>
      expect(view.getByLabelText('Confirm recurring link').props.accessibilityState.disabled).toBe(
        false,
      ),
    );
    expect(mockApi.linkRecurringBill).toHaveBeenCalledTimes(1);
    await fireEvent.press(view.getByLabelText('Confirm recurring link'));

    await waitFor(() => expect(onChanged).toHaveBeenCalledWith(updated));
    expect(mockApi.linkRecurringBill).toHaveBeenLastCalledWith(
      source.id,
      expect.objectContaining({ entryVersion: 7, paycheckVersion: 11 }),
    );
    expect(mockApi.linkRecurringBill).toHaveBeenCalledTimes(2);
  });

  it('does not reuse a successful link selection when a later create attempt fails', async () => {
    const source = entry();
    const linked = paycheck(
      {
        ...source,
        sourceRecurringBillDefinitionId: definition(3).id,
        sourceRecurringOccurrenceDate: occurrence(3).occurrenceDate,
        version: 1,
      },
      { version: 4 },
    );
    mockApi.recurringBills.mockResolvedValue({ items: [definition(3)] });
    mockApi.recurringBillTimeline.mockResolvedValue(timeline(occurrence(3)));
    mockApi.linkRecurringBill.mockResolvedValue(linked);
    mockApi.createRecurringBillFromEntry.mockRejectedValue(new Error('Create failed.'));
    mockApi.paycheck.mockResolvedValue(paycheck(source));
    const view = await renderSection(
      source,
      paycheck(source),
      jest.fn().mockResolvedValue(undefined),
    );

    await openLinkReview(view);
    await fireEvent.press(view.getByLabelText('Confirm recurring link'));
    await waitFor(() => expect(view.getByLabelText('Turn into recurring Bill')).toBeTruthy());
    await openCreateReview(view);
    await fireEvent.press(view.getByLabelText('Confirm recurring link'));

    expect(await view.findByText('Create failed.')).toBeTruthy();
    expect(view.getByText('Link to Netflix Subscription')).toBeTruthy();
    expect(mockApi.recurringBills).toHaveBeenCalledTimes(1);
    expect(mockApi.createRecurringBillFromEntry).toHaveBeenLastCalledWith(
      source.id,
      expect.objectContaining({ name: source.name, occurrenceDate: source.dueDate }),
    );
  });

  it('does not restore link-picker state when a later unlink attempt fails', async () => {
    const source = linkedEntry();
    const changed = paycheck({ ...source, version: 2 }, { version: 4 });
    mockApi.recurringBill.mockResolvedValue(definition(3));
    mockApi.recurringBills.mockResolvedValue({ items: [definition(3)] });
    mockApi.recurringBillTimeline.mockResolvedValue(timeline(occurrence(3)));
    mockApi.linkRecurringBill.mockResolvedValue(changed);
    mockApi.unlinkRecurringBill.mockRejectedValue(new Error('Unlink failed.'));
    mockApi.paycheck.mockResolvedValue(paycheck(source));
    const view = await renderSection(
      source,
      paycheck(source),
      jest.fn().mockResolvedValue(undefined),
    );

    await fireEvent.press(await view.findByLabelText('Change link'));
    await fireEvent.press(await view.findByLabelText('Choose Netflix'));
    await fireEvent.press(await view.findByLabelText('Review changes'));
    await fireEvent.press(await view.findByLabelText('Confirm recurring link'));
    await waitFor(() => expect(view.getByLabelText('Remove link')).toBeTruthy());
    await fireEvent.press(view.getByLabelText('Remove link'));
    await fireEvent.press(await view.findByLabelText('Confirm remove link'));

    expect(await view.findByText('Unlink failed.')).toBeTruthy();
    expect(view.getByLabelText('Confirm remove link')).toBeTruthy();
    expect(view.queryByText('Choose recurring Bill')).toBeNull();
    expect(mockApi.recurringBillTimeline).toHaveBeenCalledTimes(1);
  });

  it('recovers a failed change-link attempt when the original relationship is unchanged', async () => {
    const source = linkedEntry();
    const targetDefinition = {
      ...definition(4),
      id: '77777777-7777-4777-8777-777777777777',
      name: 'Hulu',
    };
    const targetOccurrence = {
      ...occurrence(4),
      definitionId: targetDefinition.id,
      name: targetDefinition.name,
      occurrenceDate: '2026-08-25',
    };
    mockApi.recurringBill.mockResolvedValue(definition(3));
    mockApi.recurringBills.mockResolvedValue({ items: [targetDefinition] });
    mockApi.recurringBillTimeline.mockResolvedValue(timeline(targetOccurrence));
    mockApi.linkRecurringBill.mockRejectedValue(new Error('Change failed.'));
    mockApi.paycheck.mockResolvedValue(paycheck(source));
    const view = await renderSection(
      source,
      paycheck(source),
      jest.fn().mockResolvedValue(undefined),
    );

    await fireEvent.press(await view.findByLabelText('Change link'));
    await fireEvent.press(await view.findByLabelText('Choose Hulu'));
    await fireEvent.press(await view.findByLabelText('Review changes'));
    await fireEvent.press(await view.findByLabelText('Confirm recurring link'));

    expect(await view.findByText('Change failed.')).toBeTruthy();
    expect(view.getByText('Link to Hulu')).toBeTruthy();
    expect(view.getByLabelText('Confirm recurring link')).toBeTruthy();
    expect(view.queryByText(/different recurring information/)).toBeNull();
  });

  it('exits stale recovery without mutating a different concurrent relationship', async () => {
    const source = entry();
    const different = paycheck({
      ...source,
      sourceRecurringBillDefinitionId: '66666666-6666-4666-8666-666666666666',
      sourceRecurringOccurrenceDate: '2026-09-21',
      version: 1,
    });
    mockApi.recurringBills.mockResolvedValue({ items: [definition(3)] });
    mockApi.recurringBillTimeline.mockResolvedValue(timeline(occurrence(3)));
    mockApi.linkRecurringBill.mockRejectedValue(new Error('Link failed.'));
    mockApi.paycheck.mockResolvedValue(different);
    const view = await renderSection(
      source,
      paycheck(source),
      jest.fn().mockResolvedValue(undefined),
    );

    await openLinkReview(view);
    await fireEvent.press(view.getByLabelText('Confirm recurring link'));

    expect(await view.findByText(/different recurring information/)).toBeTruthy();
    expect(view.queryByTestId('recurring-reconciliation-modal')).toBeNull();
    expect(mockApi.linkRecurringBill).toHaveBeenCalledTimes(1);
    expect(mockApi.unlinkRecurringBill).not.toHaveBeenCalled();
    expect(mockApi.createRecurringBillFromEntry).not.toHaveBeenCalled();
  });

  it('exits an unknown outcome when Check result discovers a different relationship', async () => {
    const source = entry();
    const different = paycheck({
      ...source,
      sourceRecurringBillDefinitionId: '88888888-8888-4888-8888-888888888888',
      sourceRecurringOccurrenceDate: '2026-10-21',
      version: 1,
    });
    mockApi.recurringBills.mockResolvedValue({ items: [definition(3)] });
    mockApi.recurringBillTimeline.mockResolvedValue(timeline(occurrence(3)));
    mockApi.linkRecurringBill.mockRejectedValue(new Error('Link failed.'));
    mockApi.paycheck
      .mockRejectedValueOnce(new Error('Refresh failed.'))
      .mockResolvedValueOnce(different);
    const view = await renderSection(
      source,
      paycheck(source),
      jest.fn().mockResolvedValue(undefined),
    );
    await openLinkReview(view);
    await fireEvent.press(view.getByLabelText('Confirm recurring link'));
    await view.findByLabelText('Check result');

    await fireEvent.press(view.getByLabelText('Check result'));

    expect(await view.findByText(/different recurring information/)).toBeTruthy();
    expect(view.queryByTestId('recurring-reconciliation-modal')).toBeNull();
    expect(mockApi.linkRecurringBill).toHaveBeenCalledTimes(1);
    expect(mockApi.unlinkRecurringBill).not.toHaveBeenCalled();
    expect(mockApi.createRecurringBillFromEntry).not.toHaveBeenCalled();
  });

  it('prevents duplicate confirmation and close while a write is pending', async () => {
    const source = entry();
    const updated = paycheck(
      {
        ...source,
        sourceRecurringBillDefinitionId: definition(3).id,
        sourceRecurringOccurrenceDate: occurrence(3).occurrenceDate,
        version: 1,
      },
      { version: 4 },
    );
    const pending = deferred<Paycheck>();
    mockApi.recurringBills.mockResolvedValue({ items: [definition(3)] });
    mockApi.recurringBillTimeline.mockResolvedValue(timeline(occurrence(3)));
    mockApi.linkRecurringBill.mockReturnValue(pending.promise);
    const onChanged = jest.fn().mockResolvedValue(undefined);
    const view = await renderSection(source, paycheck(source), onChanged);
    await openLinkReview(view);
    const user = userEvent.setup();
    const confirm = view.getByLabelText('Confirm recurring link');

    await user.press(confirm);
    await user.press(confirm);
    await waitFor(() => expect(mockApi.linkRecurringBill).toHaveBeenCalledTimes(1));
    expect(
      view.getByLabelText('Close recurring Bill workflow').props.accessibilityState.disabled,
    ).toBe(true);
    await user.press(view.getByLabelText('Close recurring Bill workflow'));
    expect(view.getByTestId('recurring-reconciliation-modal')).toBeTruthy();

    pending.resolve(updated);
    await waitFor(() => expect(onChanged).toHaveBeenCalledWith(updated));
  });
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, reject, resolve };
}

async function expectLostResponse(action: 'link' | 'unlink' | 'create') {
  const source = action === 'unlink' ? linkedEntry() : entry();
  const authoritativeEntry =
    action === 'unlink'
      ? {
          ...source,
          sourceRecurringBillDefinitionId: null,
          sourceRecurringOccurrenceDate: null,
          version: source.version + 1,
        }
      : {
          ...source,
          amountMinor: action === 'link' ? 1499 : source.amountMinor,
          dueDate: action === 'link' ? '2026-08-21' : source.dueDate,
          name: action === 'link' ? 'Netflix' : source.name,
          paymentMethod: action === 'link' ? ('AUTOPAY' as const) : source.paymentMethod,
          sourceRecurringBillDefinitionId:
            action === 'link' ? definition(3).id : '55555555-5555-4555-8555-555555555555',
          sourceRecurringOccurrenceDate: action === 'link' ? '2026-08-21' : source.dueDate,
          version: source.version + 1,
        };
  const authoritative = paycheck(authoritativeEntry, { version: 4 });
  const mutation =
    action === 'link'
      ? mockApi.linkRecurringBill
      : action === 'unlink'
        ? mockApi.unlinkRecurringBill
        : mockApi.createRecurringBillFromEntry;
  mockApi.recurringBill.mockResolvedValue(definition(3));
  mockApi.recurringBills.mockResolvedValue({ items: [definition(3)] });
  mockApi.recurringBillTimeline.mockResolvedValue(timeline(occurrence(3)));
  mutation.mockRejectedValue(new Error('Response was lost.'));
  mockApi.paycheck.mockResolvedValue(authoritative);
  const onChanged = jest.fn().mockResolvedValue(undefined);
  const view = await renderSection(source, paycheck(source), onChanged);

  if (action === 'link') await openLinkReview(view);
  if (action === 'create') await openCreateReview(view);
  if (action === 'unlink') {
    await fireEvent.press(await view.findByLabelText('Remove link'));
    await fireEvent.press(await view.findByLabelText('Confirm remove link'));
  } else {
    await fireEvent.press(await view.findByLabelText('Confirm recurring link'));
  }

  await waitFor(() => expect(onChanged).toHaveBeenCalledWith(authoritative));
  expect(mutation).toHaveBeenCalledTimes(1);
  expect(view.queryByTestId('recurring-reconciliation-modal')).toBeNull();
}

async function openLinkReview(view: Awaited<ReturnType<typeof renderSection>>) {
  await fireEvent.press(await view.findByLabelText('Link to recurring Bill'));
  await fireEvent.press(await view.findByLabelText('Choose Netflix'));
  await fireEvent.press(await view.findByLabelText('Review changes'));
  await view.findByLabelText('Confirm recurring link');
}

async function openCreateReview(view: Awaited<ReturnType<typeof renderSection>>) {
  await fireEvent.press(await view.findByLabelText('Turn into recurring Bill'));
  await fireEvent.press(await view.findByLabelText('Review occurrence'));
  await fireEvent.press(await view.findByLabelText('Review changes'));
  await view.findByLabelText('Confirm recurring link');
}

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

function linkedEntry(): Entry {
  return {
    ...entry(),
    sourceRecurringBillDefinitionId: definition(3).id,
    sourceRecurringOccurrenceDate: occurrence(3).occurrenceDate,
    version: 1,
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
