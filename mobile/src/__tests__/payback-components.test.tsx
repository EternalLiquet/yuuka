import { fireEvent, render, waitFor } from '@testing-library/react-native';

import type { Payback } from '@/api/contracts';
import { PaybackCard } from '@/features/paybacks/payback-card';
import { PaybackEditor } from '@/features/paybacks/payback-editor';

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

const payback: Payback = {
  id: '11111111-1111-4111-8111-111111111111',
  name: 'Personal loan repayment',
  originalAmountMinor: 200000,
  openingRemainingAmountMinor: 125000,
  repaidMinor: 40000,
  remainingMinor: 85000,
  progressPercent: 32,
  borrowedDate: '2026-06-12',
  source: 'Savings',
  notes: null,
  state: 'ACTIVE',
  position: 0,
  repaymentCount: 3,
  createdAt: '2026-07-12T12:00:00Z',
  updatedAt: '2026-07-12T12:30:00Z',
  version: 1,
};

describe('PaybackCard', () => {
  it('shows formatted money, repayment progress, and no internal terms', async () => {
    const view = await render(<PaybackCard onPress={jest.fn()} payback={payback} />);

    expect(view.getByText('Personal loan repayment')).toBeTruthy();
    expect(view.getByText('$850.00 left')).toBeTruthy();
    expect(view.getByText('Originally owed: $2,000.00')).toBeTruthy();
    expect(view.getByText('Tracked from: $1,250.00')).toBeTruthy();
    expect(view.getByText('Repaid in Yuuka: $400.00')).toBeTruthy();
    expect(
      view.getByLabelText('32 percent repaid since tracking began, $850.00 remaining'),
    ).toBeTruthy();
    expect(view.queryByText(/minor units|amountMinor/i)).toBeNull();
  });
});

describe('PaybackEditor', () => {
  it('mirrors the complete original amount while creating until baseline is edited', async () => {
    const view = await render(<PaybackEditor onClose={jest.fn()} onSubmit={jest.fn()} />);
    const original = view.getByLabelText('Original amount owed');
    const opening = view.getByLabelText('Balance when tracking began');

    for (const value of ['1', '12', '123', '123.', '123.4', '123.45']) {
      fireEvent.changeText(original, value);
      await waitFor(() =>
        expect(view.getByLabelText('Balance when tracking began').props.value).toBe(value),
      );
    }

    expect(opening.props.value).toBe('123.45');
  });

  it('stops mirroring after the baseline is manually edited', async () => {
    const view = await render(<PaybackEditor onClose={jest.fn()} onSubmit={jest.fn()} />);

    fireEvent.changeText(view.getByLabelText('Original amount owed'), '100.00');
    await waitFor(() =>
      expect(view.getByLabelText('Balance when tracking began').props.value).toBe('100.00'),
    );

    fireEvent.changeText(view.getByLabelText('Balance when tracking began'), '80.00');
    await waitFor(() =>
      expect(view.getByLabelText('Balance when tracking began').props.value).toBe('80.00'),
    );
    fireEvent.changeText(view.getByLabelText('Original amount owed'), '120.00');

    await waitFor(() =>
      expect(view.getByLabelText('Balance when tracking began').props.value).toBe('80.00'),
    );
  });

  it('distinguishes the historical baseline from current remaining while editing', async () => {
    const view = await render(
      <PaybackEditor onClose={jest.fn()} onSubmit={jest.fn()} payback={payback} />,
    );

    expect(view.getByLabelText('Balance when tracking began')).toBeTruthy();
    expect(view.getByText(/historical balance before you started tracking/i)).toBeTruthy();
    expect(
      view.getByText(/Current remaining is calculated from recorded repayments/i),
    ).toBeTruthy();
    expect(view.getByText('Current remaining')).toBeTruthy();
    expect(view.getByText('$850.00')).toBeTruthy();
    expect(view.getByLabelText('Balance when tracking began').props.value).toBe('1250.00');
  });
});
