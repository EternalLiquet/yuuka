import { act, fireEvent, render, waitFor } from '@testing-library/react-native';

import type { Entry, Paycheck } from '@/api/contracts';
import { PaymentProgressBar } from '@/components/progress-bar';
import { EntryRow } from '@/features/paychecks/entry-row';
import { PaycheckCard } from '@/features/paychecks/paycheck-card';
import { StatusSheet } from '@/features/paychecks/status-sheet';
import { colors as themeColors } from '@/theme/colors';

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

const entry: Entry = {
  id: '00000000-0000-0000-0000-000000000001',
  paycheckId: '00000000-0000-0000-0000-000000000010',
  paybackId: null,
  entryType: 'SPENDING_BUCKET',
  paymentMethod: null,
  name: 'Work Food',
  amountMinor: 5000,
  status: 'PROCESSING',
  position: 0,
  dueDate: null,
  accountName: null,
  payee: null,
  notes: null,
  targetMinor: null,
  targetDate: null,
  spentMinor: 2145,
  remainingMinor: 2855,
  overBudget: false,
  createdAt: '2026-07-10T12:00:00Z',
  updatedAt: '2026-07-10T13:00:00Z',
  version: 2,
};

const paycheck: Paycheck = {
  id: entry.paycheckId,
  name: 'UTILITIES 1/2',
  source: null,
  amountMinor: 193923,
  incomeDate: '2026-07-10',
  state: 'ACTIVE',
  templateSourceId: null,
  notes: null,
  allocatedMinor: 193920,
  unallocatedMinor: 3,
  allocationPercent: 100,
  postedMinor: 100000,
  processingMinor: 13052,
  notPaidMinor: 80868,
  completionPercent: 51.57,
  postedCount: 3,
  processingCount: 1,
  notPaidCount: 4,
  requiresAttention: true,
  entries: [entry],
  createdAt: '2026-07-10T12:00:00Z',
  updatedAt: '2026-07-10T13:00:00Z',
  closedAt: null,
  reopenedAt: null,
  archivedAt: null,
  version: 1,
};

describe('paycheck components', () => {
  it('prioritizes exact amount, unallocated money, and status counts on a paycheck card', async () => {
    const view = await render(<PaycheckCard onPress={jest.fn()} paycheck={paycheck} />);

    expect(view.getByText('UTILITIES 1/2')).toBeTruthy();
    expect(view.getByText('$1,939.23')).toBeTruthy();
    expect(view.getByText('$0.03')).toBeTruthy();
    expect(view.getByLabelText('1 Processing')).toBeTruthy();
  });

  it('shows status text and bucket spending without relying on color', async () => {
    const view = await render(
      <EntryRow entry={entry} onEdit={jest.fn()} onStatusPress={jest.fn()} />,
    );

    expect(view.getByLabelText('Status: Processing')).toBeTruthy();
    const spent = view.getByText('$21.45 spent');
    const remaining = view.getByText('$28.55 left');
    expect(spent.props.style).toEqual(
      expect.arrayContaining([expect.objectContaining({ color: themeColors.dark.danger })]),
    );
    expect(remaining.props.style).toEqual(
      expect.arrayContaining([expect.objectContaining({ color: themeColors.dark.posted })]),
    );
    expect(view.getByLabelText('$21.45 spent, $28.55 left')).toBeTruthy();
    expect(view.getByLabelText('Change status for Work Food')).toBeTruthy();
  });

  it('shows Bill payment method as secondary text', async () => {
    const view = await render(
      <EntryRow
        entry={{
          ...entry,
          entryType: 'BILL',
          overBudget: null,
          paymentMethod: 'MANUAL',
          remainingMinor: null,
          spentMinor: null,
        }}
        onEdit={jest.fn()}
        onStatusPress={jest.fn()}
      />,
    );

    expect(view.getByText(/Manual Pay/)).toBeTruthy();
  });

  it('shows zero remaining as left and over-budget as a positive over amount', async () => {
    const zeroView = await render(
      <EntryRow
        entry={{ ...entry, remainingMinor: 0, spentMinor: 5000 }}
        onEdit={jest.fn()}
        onStatusPress={jest.fn()}
      />,
    );
    expect(zeroView.getByText('$0.00 left').props.style).toEqual(
      expect.arrayContaining([expect.objectContaining({ color: themeColors.dark.posted })]),
    );

    const overView = await render(
      <EntryRow
        entry={{ ...entry, overBudget: true, remainingMinor: -19, spentMinor: 7519 }}
        onEdit={jest.fn()}
        onStatusPress={jest.fn()}
      />,
    );
    const over = overView.getByText('$0.19 over');
    expect(over.props.style).toEqual(
      expect.arrayContaining([expect.objectContaining({ color: themeColors.dark.danger })]),
    );
    expect(overView.getByLabelText('$75.19 spent, $0.19 over')).toBeTruthy();
  });

  it('renders mixed payment progress from money amounts instead of counts', async () => {
    const view = await render(
      <PaymentProgressBar
        allocatedMinor={10000}
        notPaidMinor={5900}
        postedMinor={2900}
        processingMinor={1200}
      />,
    );

    expect(view.getByLabelText('29% posted, 12% processing, 59% not paid')).toBeTruthy();
    expect(view.getByTestId('payment-progress-posted').props.style).toEqual(
      expect.arrayContaining([expect.objectContaining({ width: '29%' })]),
    );
    expect(view.getByTestId('payment-progress-processing').props.style).toEqual(
      expect.arrayContaining([expect.objectContaining({ width: '12%' })]),
    );
  });

  it('handles posted-only, processing-only, not-paid-only, full, and zero payment progress', async () => {
    const posted = await render(
      <PaymentProgressBar
        allocatedMinor={10000}
        notPaidMinor={0}
        postedMinor={10000}
        processingMinor={0}
      />,
    );
    expect(posted.getByLabelText('100% posted, 0% processing, 0% not paid')).toBeTruthy();
    expect(posted.getByTestId('payment-progress-posted').props.style).toEqual(
      expect.arrayContaining([expect.objectContaining({ width: '100%' })]),
    );
    expect(posted.queryByTestId('payment-progress-processing')).toBeNull();

    const processing = await render(
      <PaymentProgressBar
        allocatedMinor={10000}
        notPaidMinor={0}
        postedMinor={0}
        processingMinor={10000}
      />,
    );
    expect(processing.getByLabelText('0% posted, 100% processing, 0% not paid')).toBeTruthy();
    expect(processing.queryByTestId('payment-progress-posted')).toBeNull();
    expect(processing.getByTestId('payment-progress-processing').props.style).toEqual(
      expect.arrayContaining([expect.objectContaining({ width: '100%' })]),
    );

    const notPaid = await render(
      <PaymentProgressBar
        allocatedMinor={10000}
        notPaidMinor={10000}
        postedMinor={0}
        processingMinor={0}
      />,
    );
    expect(notPaid.getByLabelText('0% posted, 0% processing, 100% not paid')).toBeTruthy();
    expect(notPaid.queryByTestId('payment-progress-posted')).toBeNull();
    expect(notPaid.queryByTestId('payment-progress-processing')).toBeNull();

    const zero = await render(
      <PaymentProgressBar
        allocatedMinor={0}
        notPaidMinor={0}
        postedMinor={0}
        processingMinor={0}
      />,
    );
    expect(zero.getByLabelText('0% posted, 0% processing, 0% not paid')).toBeTruthy();
  });

  it('preserves effective date and note when a status save fails', async () => {
    const onSubmit = jest.fn().mockRejectedValue(new Error('Server unavailable'));
    const view = await render(
      <StatusSheet entry={entry} onClose={jest.fn()} onSubmit={onSubmit} visible />,
    );

    fireEvent.press(view.getByText('Posted'));
    fireEvent.changeText(view.getByLabelText('Effective date and time'), '2026-07-08T12:00:00Z');
    fireEvent.changeText(view.getByLabelText('Note (optional)'), 'Paid from checking');
    await act(async () => {
      fireEvent.press(view.getByLabelText('Save status'));
    });

    await waitFor(() => expect(view.getByText('Server unavailable')).toBeTruthy());
    expect(view.getByLabelText('Effective date and time').props.value).toBe('2026-07-08T12:00:00Z');
    expect(view.getByLabelText('Note (optional)').props.value).toBe('Paid from checking');
  });
});
