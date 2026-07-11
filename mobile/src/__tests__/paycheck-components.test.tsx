import { act, fireEvent, render, waitFor } from '@testing-library/react-native';

import type { Entry, Paycheck } from '@/api/contracts';
import { EntryRow } from '@/features/paychecks/entry-row';
import { PaycheckCard } from '@/features/paychecks/paycheck-card';
import { StatusSheet } from '@/features/paychecks/status-sheet';

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
  entryType: 'SPENDING_BUCKET',
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
    expect(view.getByText(/\$21\.45 spent/)).toBeTruthy();
    expect(view.getByLabelText('Change status for Work Food')).toBeTruthy();
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
