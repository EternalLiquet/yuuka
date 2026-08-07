/* eslint-disable @typescript-eslint/no-require-imports */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import type { Paycheck, RecurringBillOccurrence } from '@/api/contracts';
import { QuickAssignRecurringBillSheet } from '@/features/recurring-bills/quick-assign-recurring-bill-sheet';

const mockApi = {
  activePaychecks: jest.fn(),
  importRecurringBills: jest.fn(),
  paycheck: jest.fn(),
};
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
const second = paycheck(
  '22222222-2222-4222-8222-222222222223',
  'Second paycheck',
  '2026-08-03',
  18000,
);

function importResult(createdEntryId: string) {
  return {
    ...first,
    entries: [
      {
        accountName: 'Checking',
        amountMinor: 12000,
        createdAt: '2026-08-05T12:00:00Z',
        dueDate: '2026-08-09',
        entryType: 'BILL' as const,
        id: createdEntryId,
        name: 'Electric',
        notes: null,
        overBudget: null,
        paybackId: null,
        paycheckId: first.id,
        payee: 'Power Co',
        paymentMethod: 'AUTOPAY' as const,
        position: 0,
        remainingMinor: null,
        sinkingFundId: null,
        sourceExpenseLedgerId: null,
        sourceRecurringBillDefinitionId: occurrence.definitionId,
        sourceRecurringOccurrenceDate: occurrence.occurrenceDate,
        spentMinor: null,
        status: 'NOT_PAID' as const,
        targetDate: null,
        targetMinor: null,
        updatedAt: '2026-08-05T12:00:00Z',
        version: 0,
      },
    ],
  };
}

async function setup() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  });
  const Wrapper = ({ children }: PropsWithChildren) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  const onCreated = jest.fn();
  const onClose = jest.fn();
  const onCreatePaycheck = jest.fn();
  const onOpenImport = jest.fn();
  const onReviewImports = jest.fn();
  const onViewTimeline = jest.fn();
  const refreshOccurrence = jest.fn().mockResolvedValue(occurrence);
  const props = {
    assignmentIdentity: {
      definitionId: occurrence.definitionId,
      occurrenceDate: occurrence.occurrenceDate,
    },
    occurrence,
    occurrenceResolved: true,
    onClose,
    onCreatePaycheck,
    onCreated,
    onOpenImport,
    onReviewImports,
    onViewTimeline,
    refreshOccurrence,
  };
  const rendered = await render(<QuickAssignRecurringBillSheet {...props} />, {
    wrapper: Wrapper,
  });
  return {
    client,
    onClose,
    onCreatePaycheck,
    onCreated,
    onOpenImport,
    onReviewImports,
    onViewTimeline,
    refreshOccurrence,
    rerenderOccurrence: (nextOccurrence: RecurringBillOccurrence | null) =>
      rendered.rerender(<QuickAssignRecurringBillSheet {...props} occurrence={nextOccurrence} />),
    view: rendered,
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
      items: [first, second, short],
      page: 0,
      size: 100,
      totalItems: 3,
      totalPages: 1,
    });
    mockApi.paycheck.mockResolvedValue(first);
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
    mockApi.importRecurringBills.mockResolvedValue(importResult(createdEntryId));
    const { onCreated, view } = await setup();
    await fireEvent.press(await view.findByLabelText(/First paycheck/));
    fireEvent.press(view.getByLabelText('Confirm import'));
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

  it('keeps invalid amount feedback visible in the editor and does not import', async () => {
    const { view } = await setup();
    await fireEvent.press(await view.findByLabelText('Edit amount'));
    await fireEvent.changeText(view.getByLabelText('Amount for this paycheck'), '12.345');
    await fireEvent.press(view.getByLabelText('This paycheck only'));

    expect(
      view.getByText('Enter a valid money amount with no more than two decimal places.'),
    ).toBeTruthy();
    expect(view.getByLabelText('Amount for this paycheck')).toBeTruthy();
    expect(mockApi.importRecurringBills).not.toHaveBeenCalled();
  });

  it.each([
    {
      action: 'Open existing import',
      imports: [
        {
          entryId: '33333333-3333-4333-8333-333333333351',
          paycheckId: first.id,
          paycheckName: first.name,
          status: 'NOT_PAID' as const,
        },
      ],
    },
    {
      action: 'Review existing imports',
      imports: [
        {
          entryId: '33333333-3333-4333-8333-333333333352',
          paycheckId: first.id,
          paycheckName: first.name,
          status: 'NOT_PAID' as const,
        },
        {
          entryId: '33333333-3333-4333-8333-333333333353',
          paycheckId: second.id,
          paycheckName: second.name,
          status: 'POSTED' as const,
        },
      ],
    },
  ])(
    'reacts to a live prop update with $action without losing form state',
    async ({ action, imports }) => {
      const { onOpenImport, onReviewImports, rerenderOccurrence, view } = await setup();
      await fireEvent.press(await view.findByLabelText('Edit amount'));
      await fireEvent.changeText(view.getByLabelText('Amount for this paycheck'), '130.00');
      await fireEvent.press(view.getByLabelText('Update typical amount'));
      await fireEvent.press(view.getByLabelText(/First paycheck/));

      await rerenderOccurrence({
        ...occurrence,
        definitionVersion: 5,
        importCount: imports.length,
        imports,
      });

      expect(view.getByText('$130.00')).toBeTruthy();
      expect(view.getByLabelText(/First paycheck/).props.accessibilityState.checked).toBe(true);
      expect(view.getByLabelText('Confirm import').props.accessibilityState.disabled).toBe(true);
      await fireEvent.press(view.getByLabelText('Confirm import'));
      expect(mockApi.importRecurringBills).not.toHaveBeenCalled();
      await fireEvent.press(view.getByLabelText(action));
      if (imports.length === 1)
        expect(onOpenImport).toHaveBeenCalledWith(first.id, imports[0].entryId);
      else expect(onReviewImports).toHaveBeenCalledWith(expect.objectContaining({ imports }));
    },
  );

  it('keeps a live missing occurrence visible but never submit-eligible', async () => {
    const { onViewTimeline, rerenderOccurrence, view } = await setup();
    await fireEvent.press(await view.findByLabelText(/First paycheck/));

    await rerenderOccurrence(null);

    expect(view.getByTestId('quick-assignment-modal').props.visible).toBe(true);
    expect(view.getByText(/changed or is no longer available/)).toBeTruthy();
    expect(view.getByLabelText('Confirm import').props.accessibilityState.disabled).toBe(true);
    await fireEvent.press(view.getByLabelText('Confirm import'));
    expect(mockApi.importRecurringBills).not.toHaveBeenCalled();
    await fireEvent.press(view.getByLabelText('View Timeline'));
    expect(onViewTimeline).toHaveBeenCalledTimes(1);
  });

  function registerInteractionRegressionTests() {
    it('blocks dismissal and competing actions during one in-flight import', async () => {
      const pending = deferred<ReturnType<typeof importResult>>();
      const createdEntryId = '33333333-3333-4333-8333-333333333334';
      mockApi.importRecurringBills.mockReturnValue(pending.promise);
      const { onClose, onCreatePaycheck, onCreated, view } = await setup();
      await fireEvent.press(await view.findByLabelText(/First paycheck/));
      fireEvent.press(view.getByLabelText('Confirm import'));
      await waitFor(() => expect(mockApi.importRecurringBills).toHaveBeenCalledTimes(1));

      const close = view.getByLabelText('Close quick assignment');
      expect(close.props.accessibilityState.disabled).toBe(true);
      fireEvent.press(close);
      const assignmentModal = view.getByTestId('quick-assignment-modal');
      assignmentModal.props.onRequestClose();
      fireEvent.press(view.getByLabelText(/Second paycheck/));
      fireEvent.press(view.getByLabelText('Edit amount'));
      fireEvent.press(view.getByLabelText('Create paycheck instead'));
      fireEvent.press(view.getByLabelText('Confirm import'));

      expect(onClose).not.toHaveBeenCalled();
      expect(onCreatePaycheck).not.toHaveBeenCalled();
      expect(view.getByLabelText(/First paycheck/).props.accessibilityState.checked).toBe(true);
      expect(view.getByLabelText(/Second paycheck/).props.accessibilityState.checked).toBe(false);
      expect(view.queryByLabelText('Amount for this paycheck')).toBeNull();
      expect(mockApi.importRecurringBills).toHaveBeenCalledTimes(1);

      pending.resolve(importResult(createdEntryId));
      await waitFor(() => expect(onCreated).toHaveBeenCalledWith(first.id, createdEntryId));
      expect(onCreated).toHaveBeenCalledTimes(1);
      expect(mockApi.importRecurringBills).toHaveBeenCalledTimes(1);
    });

    it('blocks live occurrence navigation while an import remains in flight', async () => {
      const pending = deferred<ReturnType<typeof importResult>>();
      const createdEntryId = '33333333-3333-4333-8333-333333333335';
      const oneImport = {
        ...occurrence,
        importCount: 1,
        imports: [
          {
            entryId: '33333333-3333-4333-8333-333333333336',
            paycheckId: first.id,
            paycheckName: first.name,
            status: 'NOT_PAID' as const,
          },
        ],
      };
      const multipleImports = {
        ...occurrence,
        importCount: 2,
        imports: [
          ...oneImport.imports,
          {
            entryId: '33333333-3333-4333-8333-333333333337',
            paycheckId: second.id,
            paycheckName: second.name,
            status: 'POSTED' as const,
          },
        ],
      };
      mockApi.importRecurringBills.mockReturnValue(pending.promise);
      const { onCreated, onOpenImport, onReviewImports, onViewTimeline, rerenderOccurrence, view } =
        await setup();
      await fireEvent.press(await view.findByLabelText(/First paycheck/));
      fireEvent.press(view.getByLabelText('Confirm import'));
      await waitFor(() => expect(mockApi.importRecurringBills).toHaveBeenCalledTimes(1));

      await rerenderOccurrence(oneImport);
      const openImport = view.getByLabelText('Open existing import');
      expect(openImport.props.accessibilityState.disabled).toBe(true);
      fireEvent.press(openImport);
      expect(onOpenImport).not.toHaveBeenCalled();

      await rerenderOccurrence(multipleImports);
      const reviewImports = view.getByLabelText('Review existing imports');
      expect(reviewImports.props.accessibilityState.disabled).toBe(true);
      fireEvent.press(reviewImports);
      expect(onReviewImports).not.toHaveBeenCalled();

      await rerenderOccurrence(null);
      const viewTimeline = view.getByLabelText('View Timeline');
      expect(viewTimeline.props.accessibilityState.disabled).toBe(true);
      fireEvent.press(viewTimeline);
      expect(onViewTimeline).not.toHaveBeenCalled();

      pending.resolve(importResult(createdEntryId));
      await waitFor(() => expect(onCreated).toHaveBeenCalledWith(first.id, createdEntryId));
      expect(onCreated).toHaveBeenCalledTimes(1);
      expect(onOpenImport).not.toHaveBeenCalled();
      expect(onReviewImports).not.toHaveBeenCalled();
      expect(onViewTimeline).not.toHaveBeenCalled();
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
  }

  it('reconciles a lost import response without allowing a second POST', async () => {
    const createdEntryId = '33333333-3333-4333-8333-333333333335';
    mockApi.importRecurringBills.mockRejectedValue(new Error('response lost'));
    mockApi.paycheck.mockResolvedValue(importResult(createdEntryId));
    const { onCreated, view } = await setup();

    await fireEvent.press(await view.findByLabelText(/First paycheck/));
    await fireEvent.press(view.getByLabelText('Confirm import'));

    await waitFor(() => expect(onCreated).toHaveBeenCalledWith(first.id, createdEntryId));
    expect(onCreated).toHaveBeenCalledTimes(1);
    expect(mockApi.importRecurringBills).toHaveBeenCalledTimes(1);
    await waitFor(() =>
      expect(view.getByLabelText('Confirm import').props.accessibilityState.busy).toBe(false),
    );
    await fireEvent.press(view.getByLabelText('Confirm import'));
    expect(mockApi.importRecurringBills).toHaveBeenCalledTimes(1);
  });

  it('blocks retry while the result is unknown and later confirms the created entry', async () => {
    const createdEntryId = '33333333-3333-4333-8333-333333333336';
    mockApi.importRecurringBills.mockRejectedValue(new Error('response lost'));
    mockApi.paycheck.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({
      ...importResult(createdEntryId),
      entries: [{ ...importResult(createdEntryId).entries[0], amountMinor: 13000 }],
    });
    const { onCreated, view } = await setup();
    await fireEvent.press(await view.findByLabelText('Edit amount'));
    await fireEvent.changeText(view.getByLabelText('Amount for this paycheck'), '130.00');
    await fireEvent.press(view.getByLabelText('Update typical amount'));
    await fireEvent.press(view.getByLabelText(/First paycheck/));
    await fireEvent.press(view.getByLabelText('Confirm import'));

    expect(await view.findByText(/could not confirm whether this Bill was added/)).toBeTruthy();
    expect(view.getByText('$130.00')).toBeTruthy();
    expect(view.getByLabelText(/First paycheck/).props.accessibilityState.checked).toBe(true);
    expect(view.getByLabelText('Confirm import').props.accessibilityState.disabled).toBe(true);
    expect(view.getByLabelText('Check result')).toBeTruthy();

    await fireEvent.press(view.getByLabelText('Check result'));
    await waitFor(() => expect(onCreated).toHaveBeenCalledWith(first.id, createdEntryId));
    expect(mockApi.importRecurringBills).toHaveBeenCalledTimes(1);
    await waitFor(() =>
      expect(view.getByLabelText('Confirm import').props.accessibilityState.busy).toBe(false),
    );
  });

  it('keeps the pre-submit baseline when the Active cache gains the committed entry', async () => {
    const createdEntryId = '33333333-3333-4333-8333-333333333338';
    const committed = importResult(createdEntryId);
    mockApi.importRecurringBills.mockRejectedValue(new Error('response lost'));
    mockApi.paycheck.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(committed);
    const { client, onCreated, view } = await setup();
    await fireEvent.press(await view.findByLabelText(/First paycheck/));
    await fireEvent.press(view.getByLabelText('Confirm import'));
    await view.findByLabelText('Check result');

    client.setQueryData(['paychecks', 'active'], {
      hasNext: false,
      items: [committed, second, short],
      page: 0,
      size: 100,
      totalItems: 3,
      totalPages: 1,
    });
    await fireEvent.press(view.getByLabelText('Check result'));

    await waitFor(() => expect(onCreated).toHaveBeenCalledWith(first.id, createdEntryId));
    expect(onCreated).toHaveBeenCalledTimes(1);
    expect(mockApi.importRecurringBills).toHaveBeenCalledTimes(1);
    await fireEvent.press(view.getByLabelText('Confirm import'));
    expect(mockApi.importRecurringBills).toHaveBeenCalledTimes(1);
  });

  it('keeps an unknown attempt authoritative when a live prop update shows an import', async () => {
    const createdEntryId = '33333333-3333-4333-8333-333333333354';
    const committed = importResult(createdEntryId);
    mockApi.importRecurringBills.mockRejectedValue(new Error('response lost'));
    mockApi.paycheck.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(committed);
    const { onCreated, rerenderOccurrence, view } = await setup();
    await fireEvent.press(await view.findByLabelText(/First paycheck/));
    await fireEvent.press(view.getByLabelText('Confirm import'));
    await view.findByLabelText('Check result');

    await rerenderOccurrence({
      ...occurrence,
      importCount: 1,
      imports: [
        {
          entryId: createdEntryId,
          paycheckId: first.id,
          paycheckName: first.name,
          status: 'NOT_PAID',
        },
      ],
    });

    expect(view.getByLabelText('Confirm import').props.accessibilityState.disabled).toBe(true);
    expect(view.getByLabelText('Check result')).toBeTruthy();
    expect(view.queryByLabelText('Open existing import')).toBeNull();
    expect(onCreated).not.toHaveBeenCalled();
    await fireEvent.press(view.getByLabelText('Check result'));
    await waitFor(() => expect(onCreated).toHaveBeenCalledWith(first.id, createdEntryId));
    expect(mockApi.importRecurringBills).toHaveBeenCalledTimes(1);
  });

  it('uses the latest paycheck and definition versions after an unknown no-match', async () => {
    const latest = { ...first, version: 7 };
    const refreshedOccurrence = { ...occurrence, definitionVersion: 5 };
    mockApi.importRecurringBills
      .mockRejectedValueOnce(new Error('response lost'))
      .mockResolvedValueOnce({
        ...importResult('33333333-3333-4333-8333-333333333337'),
        entries: [
          {
            ...importResult('33333333-3333-4333-8333-333333333337').entries[0],
            amountMinor: 13000,
          },
        ],
      });
    mockApi.paycheck.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(latest);
    const { onCreated, refreshOccurrence, view } = await setup();
    refreshOccurrence.mockResolvedValue(refreshedOccurrence);
    await fireEvent.press(await view.findByLabelText('Edit amount'));
    await fireEvent.changeText(view.getByLabelText('Amount for this paycheck'), '130.00');
    await fireEvent.press(view.getByLabelText('Update typical amount'));
    await fireEvent.press(await view.findByLabelText(/First paycheck/));
    await fireEvent.press(view.getByLabelText('Confirm import'));
    await view.findByLabelText('Check result');

    await fireEvent.press(view.getByLabelText('Check result'));
    await waitFor(() =>
      expect(view.getByLabelText('Confirm import').props.accessibilityState.disabled).toBe(false),
    );
    await fireEvent.press(view.getByLabelText('Confirm import'));

    await waitFor(() => expect(mockApi.importRecurringBills).toHaveBeenCalledTimes(2));
    expect(mockApi.importRecurringBills.mock.calls[1]).toEqual([
      first.id,
      7,
      [
        {
          amountMinor: 13000,
          definitionId: occurrence.definitionId,
          definitionVersion: 5,
          occurrenceDate: occurrence.occurrenceDate,
          updateTypicalAmount: true,
        },
      ],
    ]);
    await waitFor(() => expect(onCreated).toHaveBeenCalledTimes(1));
    await waitFor(() =>
      expect(view.getByLabelText('Confirm import').props.accessibilityState.busy).toBe(false),
    );
  });

  it.each([
    {
      action: 'Open existing import',
      imports: [
        {
          entryId: '33333333-3333-4333-8333-333333333341',
          paycheckId: first.id,
          paycheckName: first.name,
          status: 'NOT_PAID' as const,
        },
      ],
    },
    {
      action: 'Review existing imports',
      imports: [
        {
          entryId: '33333333-3333-4333-8333-333333333342',
          paycheckId: first.id,
          paycheckName: first.name,
          status: 'NOT_PAID' as const,
        },
        {
          entryId: '33333333-3333-4333-8333-333333333343',
          paycheckId: second.id,
          paycheckName: second.name,
          status: 'PROCESSING' as const,
        },
      ],
    },
  ])(
    'blocks another POST when refreshed occurrence offers $action',
    async ({ action, imports }) => {
      mockApi.importRecurringBills.mockRejectedValue(new Error('conflict'));
      mockApi.paycheck.mockResolvedValue({ ...first, version: 4 });
      const { onOpenImport, onReviewImports, refreshOccurrence, view } = await setup();
      const assigned = { ...occurrence, importCount: imports.length, imports };
      refreshOccurrence.mockResolvedValue(assigned);
      await fireEvent.press(await view.findByLabelText(/First paycheck/));
      await fireEvent.press(view.getByLabelText('Confirm import'));

      const actionButton = await view.findByLabelText(action);
      expect(actionButton.props.accessibilityState.disabled).toBe(false);
      expect(view.getByLabelText('Confirm import').props.accessibilityState.disabled).toBe(true);
      await fireEvent.press(actionButton);
      if (imports.length === 1)
        expect(onOpenImport).toHaveBeenCalledWith(first.id, imports[0].entryId);
      else expect(onReviewImports).toHaveBeenCalledWith(assigned);
      expect(mockApi.importRecurringBills).toHaveBeenCalledTimes(1);
    },
  );

  it('keeps the modal visible with a safe exit when the occurrence disappears', async () => {
    mockApi.importRecurringBills.mockRejectedValue(new Error('conflict'));
    mockApi.paycheck.mockResolvedValue({ ...first, version: 4 });
    const { onViewTimeline, refreshOccurrence, view } = await setup();
    refreshOccurrence.mockResolvedValue(null);
    await fireEvent.press(await view.findByLabelText(/First paycheck/));
    await fireEvent.press(view.getByLabelText('Confirm import'));

    expect(await view.findByText(/changed or is no longer available/)).toBeTruthy();
    expect(view.getByTestId('quick-assignment-modal').props.visible).toBe(true);
    expect(view.getByLabelText('Confirm import').props.accessibilityState.disabled).toBe(true);
    const viewTimeline = view.getByLabelText('View Timeline');
    expect(viewTimeline.props.accessibilityState.disabled).toBe(false);
    await fireEvent.press(viewTimeline);
    expect(onViewTimeline).toHaveBeenCalledTimes(1);
    expect(mockApi.importRecurringBills).toHaveBeenCalledTimes(1);
  });

  it('renders authoritative shortfall values and prevents retry when the paycheck no longer fits', async () => {
    mockApi.activePaychecks.mockResolvedValue({
      hasNext: false,
      items: [first],
      page: 0,
      size: 100,
      totalItems: 1,
      totalPages: 1,
    });
    mockApi.importRecurringBills.mockRejectedValue(new Error('conflict'));
    mockApi.paycheck.mockResolvedValue({ ...first, unallocatedMinor: 5000, version: 4 });
    const { view } = await setup();
    await fireEvent.press(await view.findByLabelText(/First paycheck/));
    await fireEvent.press(view.getByLabelText('Confirm import'));

    expect(
      await view.findByLabelText(/First paycheck.*\$50.00 currently.*short by \$70.00/),
    ).toBeTruthy();
    expect(view.getByText('Short by $70.00')).toBeTruthy();
    expect(view.getByText(/No Active paycheck has enough unallocated money/)).toBeTruthy();
    expect(view.getByLabelText('Confirm import').props.accessibilityState.disabled).toBe(true);
  });

  it.each(['CLOSED', 'ARCHIVED'] as const)(
    'prevents retry when the authoritative paycheck is %s',
    async (state) => {
      mockApi.activePaychecks.mockResolvedValue({
        hasNext: false,
        items: [first],
        page: 0,
        size: 100,
        totalItems: 1,
        totalPages: 1,
      });
      mockApi.importRecurringBills.mockRejectedValue(new Error('conflict'));
      mockApi.paycheck.mockResolvedValue({ ...first, state, version: 4 });
      const { view } = await setup();
      await fireEvent.press(await view.findByLabelText(/First paycheck/));
      await fireEvent.press(view.getByLabelText('Confirm import'));

      expect(
        await view.findByLabelText(
          new RegExp(`${first.name}.*${state === 'CLOSED' ? 'Closed' : 'Archived'}, unavailable`),
        ),
      ).toBeTruthy();
      expect(view.getByLabelText('Confirm import').props.accessibilityState.disabled).toBe(true);
      await fireEvent.press(view.getByLabelText('Confirm import'));
      expect(mockApi.importRecurringBills).toHaveBeenCalledTimes(1);
    },
  );

  it('puts checking progress only on Check result and blocks duplicate checks', async () => {
    const lookup = deferred<Paycheck>();
    mockApi.importRecurringBills.mockRejectedValue(new Error('response lost'));
    mockApi.paycheck
      .mockRejectedValueOnce(new Error('offline'))
      .mockReturnValueOnce(lookup.promise);
    const { view } = await setup();
    await fireEvent.press(await view.findByLabelText(/First paycheck/));
    await fireEvent.press(view.getByLabelText('Confirm import'));
    await view.findByLabelText('Check result');

    fireEvent.press(view.getByLabelText('Check result'));
    await waitFor(() =>
      expect(view.getByLabelText('Check result').props.accessibilityState.busy).toBe(true),
    );
    expect(view.getByLabelText('Confirm import').props.accessibilityState.busy).toBe(false);
    expect(view.getByLabelText('Confirm import').props.accessibilityState.disabled).toBe(true);
    fireEvent.press(view.getByLabelText('Check result'));
    expect(mockApi.paycheck).toHaveBeenCalledTimes(2);
    lookup.resolve(first);
    await waitFor(() => expect(view.queryByLabelText('Check result')).toBeNull());
  });

  registerInteractionRegressionTests();
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, reject, resolve };
}
