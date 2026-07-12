import { render } from '@testing-library/react-native';

import type { Payback } from '@/api/contracts';
import { PaybackCard } from '@/features/paybacks/payback-card';

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
