import { cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';

import type { Entry, Payback, SinkingFund } from '@/api/contracts';
import { EntryEditor } from '@/features/paychecks/entry-editor';

jest.mock('@/settings/settings-provider', () => ({
  useSettings: () => ({
    settings: {
      apiBaseUrl: 'http://localhost:8080/api/v1',
      currencyCode: 'USD',
      theme: 'dark',
      timezone: 'America/Indianapolis',
    },
  }),
}));

afterEach(() => {
  cleanup();
});

describe('EntryEditor', () => {
  it('shows an accessible manual-payment checkbox for Bills', async () => {
    const view = await render(
      <EntryEditor entry={null} onClose={jest.fn()} onSubmit={jest.fn()} visible />,
    );

    const checkbox = await view.findByLabelText('I need to pay this manually');
    expect(checkbox.props.accessibilityRole).toBe('checkbox');
    expect(checkbox.props.accessibilityState.checked).toBe(false);

    await fireEvent.press(checkbox);
    expect(
      view.getByLabelText('I need to pay this manually').props.accessibilityState.checked,
    ).toBe(true);
  });

  it('hides and clears manual payment when changing away from Bill', async () => {
    const onSubmit = jest.fn().mockResolvedValue(undefined);
    const view = await render(
      <EntryEditor
        entry={entry({ paymentMethod: 'MANUAL' })}
        onClose={jest.fn()}
        onSubmit={onSubmit}
        visible
      />,
    );

    expect(
      (await view.findByLabelText('I need to pay this manually')).props.accessibilityState.checked,
    ).toBe(true);
    await fireEvent.press(view.getByTestId('segmented-Entry type-SPENDING_BUCKET'));

    expect(view.queryByLabelText('I need to pay this manually')).toBeNull();
    await fireEvent.press(view.getByTestId('segmented-Entry type-BILL'));
    expect(
      (await view.findByLabelText('I need to pay this manually')).props.accessibilityState.checked,
    ).toBe(false);
  });

  it('opens Payback choices in a compact selector instead of rendering every option inline', async () => {
    const view = await render(
      <EntryEditor
        entry={null}
        onClose={jest.fn()}
        onSubmit={jest.fn()}
        paybacks={[
          payback({ id: '11111111-1111-4111-8111-111111111102', name: 'Second', position: 1 }),
          payback({ id: '11111111-1111-4111-8111-111111111101', name: 'First', position: 0 }),
          payback({
            id: '11111111-1111-4111-8111-111111111103',
            name: 'Settled',
            position: 2,
            state: 'PAID_OFF',
          }),
        ]}
        visible
      />,
    );

    expect(view.getByText('Choose Payback')).toBeTruthy();
    expect(view.queryByText('First')).toBeNull();
    expect(view.queryByText('Second')).toBeNull();

    await fireEvent.press(view.getByLabelText('Apply to Payback, selected No Payback'));

    expect(view.getByText('First')).toBeTruthy();
    expect(view.getByText('Second')).toBeTruthy();
    expect(view.queryByText('Settled')).toBeNull();
    const optionLabels = view
      .getAllByText(/No Payback|First|Second/)
      .map((node) => node.props.children);
    expect(optionLabels.slice(-3)).toEqual(['No Payback', 'First', 'Second']);
  });

  it('clears an existing Payback assignment with No Payback', async () => {
    const onSubmit = jest.fn().mockResolvedValue(undefined);
    const view = await render(
      <EntryEditor
        entry={entry({ paybackId: '11111111-1111-4111-8111-111111111101' })}
        onClose={jest.fn()}
        onSubmit={onSubmit}
        paybacks={[payback({ id: '11111111-1111-4111-8111-111111111101', name: 'Car repair' })]}
        visible
      />,
    );

    await fireEvent.press(view.getByLabelText('Apply to Payback, selected Car repair'));
    await fireEvent.press(view.getByText('No Payback'));
    await fireEvent.press(view.getByLabelText('Save entry'));

    await waitFor(() => expect(onSubmit).toHaveBeenCalled());
    expect(onSubmit.mock.calls[0][0]).toMatchObject({ paybackId: null });
  });

  it('keeps Payback and persistent Sinking Fund selections mutually exclusive', async () => {
    const onSubmit = jest.fn().mockResolvedValue(undefined);
    const view = await render(
      <EntryEditor
        entry={entry({
          entryType: 'SINKING_FUND',
          paybackId: '11111111-1111-4111-8111-111111111101',
        })}
        onClose={jest.fn()}
        onSubmit={onSubmit}
        paybacks={[payback({ id: '11111111-1111-4111-8111-111111111101', name: 'Car repair' })]}
        sinkingFunds={[
          sinkingFund({ id: '11111111-1111-4111-8111-111111111301', name: 'Car fund' }),
        ]}
        visible
      />,
    );

    await fireEvent.press(view.getByLabelText('Planned Savings, selected No planned savings'));
    await fireEvent.press(view.getByText('Car fund'));
    await fireEvent.press(view.getByLabelText('Save entry'));

    await waitFor(() => expect(onSubmit).toHaveBeenCalled());
    expect(onSubmit.mock.calls[0][0]).toMatchObject({
      paybackId: null,
      sinkingFundId: '11111111-1111-4111-8111-111111111301',
    });

    onSubmit.mockClear();
    await fireEvent.press(view.getByLabelText('Apply to Payback, selected No Payback'));
    await fireEvent.press(view.getByText('Car repair'));
    await fireEvent.press(view.getByLabelText('Save entry'));

    await waitFor(() => expect(onSubmit).toHaveBeenCalled());
    expect(onSubmit.mock.calls[0][0]).toMatchObject({
      paybackId: '11111111-1111-4111-8111-111111111101',
      sinkingFundId: null,
    });
  });

  it('shows Payback selector loading, error retry, and empty states', async () => {
    const retry = jest.fn();
    const loading = await render(
      <EntryEditor entry={null} onClose={jest.fn()} onSubmit={jest.fn()} paybacksLoading visible />,
    );
    await fireEvent.press(loading.getByLabelText('Apply to Payback, selected No Payback'));
    expect(loading.getAllByText('Loading Paybacks...').length).toBeGreaterThanOrEqual(1);

    const errored = await render(
      <EntryEditor
        entry={null}
        onClose={jest.fn()}
        onRetryPaybacks={retry}
        onSubmit={jest.fn()}
        paybacksError="Paybacks could not be loaded."
        visible
      />,
    );
    await fireEvent.press(errored.getByLabelText('Apply to Payback, selected No Payback'));
    await fireEvent.press(errored.getByLabelText('Retry'));
    expect(retry).toHaveBeenCalled();

    const empty = await render(
      <EntryEditor entry={null} onClose={jest.fn()} onSubmit={jest.fn()} visible />,
    );
    await fireEvent.press(empty.getByLabelText('Apply to Payback, selected No Payback'));
    expect(empty.getByText('No active Paybacks')).toBeTruthy();
  });

  it('preserves entry form input when a save fails', async () => {
    const onSubmit = jest.fn().mockRejectedValue(new Error('Server unavailable'));
    const view = await render(
      <EntryEditor entry={null} onClose={jest.fn()} onSubmit={onSubmit} visible />,
    );

    const name = await view.findByLabelText('Name');
    const amount = await view.findByLabelText('Amount');
    const notes = await view.findByLabelText('Notes (optional)');

    fireEvent.changeText(name, 'Groceries');
    fireEvent.changeText(amount, '150.00');
    fireEvent.changeText(notes, 'Weekly food');
    await fireEvent.press(view.getByLabelText('Save entry'));

    await waitFor(() => expect(view.getByText('Server unavailable')).toBeTruthy());
    expect(view.getByLabelText('Name').props.value).toBe('Groceries');
    expect(view.getByLabelText('Amount').props.value).toBe('150.00');
    expect(view.getByLabelText('Notes (optional)').props.value).toBe('Weekly food');
  });
});

function payback(overrides: Partial<Payback> = {}): Payback {
  return {
    borrowedDate: '2026-07-12',
    createdAt: '2026-07-12T12:00:00Z',
    id: '11111111-1111-4111-8111-111111111101',
    name: 'Personal loan',
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
    ...overrides,
  };
}

function sinkingFund(overrides: Partial<SinkingFund> = {}): SinkingFund {
  return {
    archivedAt: null,
    createdAt: '2026-07-12T12:00:00Z',
    currentBalanceMinor: 0,
    id: '11111111-1111-4111-8111-111111111301',
    name: 'Sinking fund',
    notes: null,
    position: 0,
    progressPercent: null,
    remainingTargetMinor: null,
    state: 'ACTIVE',
    targetDate: null,
    targetMinor: null,
    transactionCount: 0,
    updatedAt: '2026-07-12T12:30:00Z',
    version: 0,
    ...overrides,
  };
}

function entry(overrides: Partial<Entry> = {}): Entry {
  return {
    accountName: null,
    amountMinor: 3000,
    createdAt: '2026-07-12T12:00:00Z',
    dueDate: null,
    entryType: 'BILL',
    paymentMethod: 'AUTOPAY',
    id: '11111111-1111-4111-8111-111111111201',
    name: 'Repayment',
    notes: null,
    overBudget: null,
    paybackId: null,
    payee: null,
    paycheckId: '11111111-1111-4111-8111-111111111200',
    position: 0,
    remainingMinor: null,
    sourceRecurringBillDefinitionId: null,
    sourceRecurringOccurrenceDate: null,
    spentMinor: null,
    status: 'NOT_PAID',
    targetDate: null,
    targetMinor: null,
    updatedAt: '2026-07-12T12:30:00Z',
    version: 0,
    ...overrides,
  };
}
