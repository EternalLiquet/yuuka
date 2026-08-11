import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import type { SpendingBucketInsights } from '@/api/contracts';

import SpendingInsightsScreen from '../../app/spending-buckets/insights';

const mockBack = jest.fn();
const mockInsights = jest.fn();

jest.mock('expo-router', () => ({ useRouter: () => ({ back: mockBack }) }));
jest.mock('@/api/use-yuuka-api', () => ({
  useYuukaApi: () => ({ spendingBucketInsights: mockInsights }),
}));
jest.mock('@/settings/settings-provider', () => ({
  useSettings: () => ({ settings: { currencyCode: 'USD', theme: 'dark' } }),
}));

const overall: SpendingBucketInsights = {
  asOfDate: '2026-07-15',
  availableBucketNames: ['Gas', 'Gasoline'],
  points: [
    {
      budgetedMinor: 5000,
      incomeDate: '2026-07-01',
      matchingBucketCount: 2,
      netMinor: 1000,
      paycheckId: '11111111-1111-4111-8111-111111111111',
      paycheckName: 'First paycheck',
      spentMinor: 4000,
    },
    {
      budgetedMinor: 5000,
      incomeDate: '2026-07-08',
      matchingBucketCount: 1,
      netMinor: -500,
      paycheckId: '22222222-2222-4222-8222-222222222222',
      paycheckName: 'Second paycheck',
      spentMinor: 5500,
    },
    {
      budgetedMinor: 5000,
      incomeDate: '2026-07-15',
      matchingBucketCount: 1,
      netMinor: 0,
      paycheckId: '33333333-3333-4333-8333-333333333333',
      paycheckName: 'Third paycheck',
      spentMinor: 5000,
    },
  ],
  qualifyingPaycheckCount: 3,
  recentPaycheckLimit: 12,
  scope: 'ALL',
  selectedBucketName: null,
};

describe('Spending Insights route', () => {
  afterEach(() => {
    cleanup();
    jest.clearAllMocks();
  });

  it('shows non-color graph semantics and exact accessible paycheck values', async () => {
    mockInsights.mockResolvedValue(overall);
    const view = await renderScreen();
    expect(await view.findByText('● Budgeted — solid line')).toBeTruthy();
    expect(view.getByText('■ Spent - - dashed line')).toBeTruthy();
    expect(view.getByText('↑ Under budget')).toBeTruthy();
    expect(view.getByText('↓ Over budget')).toBeTruthy();
    expect(view.getByText('Exactly on budget · Exactly on budget')).toBeTruthy();
    expect(
      view.getByLabelText(
        'First paycheck, Jul 1, 2026. Budgeted $50.00. Spent $40.00. Under by $10.00. 2 matching buckets.',
      ),
    ).toBeTruthy();
    expect(view.getByText('2 same-name bucket entries combined')).toBeTruthy();
    expect(view.getByText(/trimmed names match exactly/)).toBeTruthy();
  });

  it('loads a selected bucket as sparse history without manufactured zero points', async () => {
    mockInsights.mockImplementation(async (name?: string) =>
      name === 'Gas'
        ? {
            ...overall,
            points: [overall.points[1]],
            scope: 'BUCKET_NAME',
            selectedBucketName: 'Gas',
          }
        : overall,
    );
    const view = await renderScreen();
    await view.findByText('First paycheck');
    await fireEvent.press(view.getByLabelText('Gas'));
    await waitFor(() => expect(mockInsights).toHaveBeenCalledWith('Gas'));
    expect(await view.findByLabelText('Gas insight history')).toBeTruthy();
    expect(view.getByText('Second paycheck')).toBeTruthy();
    expect(view.queryByText('First paycheck')).toBeNull();
  });

  it('keeps overall history on drill-down failure', async () => {
    mockInsights.mockImplementation(async (name?: string) => {
      if (name === 'Gas') throw new Error('offline');
      return overall;
    });
    const view = await renderScreen();
    await view.findByText('First paycheck');
    await fireEvent.press(view.getByLabelText('Gas'));
    await waitFor(() => expect(mockInsights).toHaveBeenCalledWith('Gas'));
    expect(await view.findByText(/Showing usable overall history/)).toBeTruthy();
    expect(view.getByText('First paycheck')).toBeTruthy();
  });

  it('shows an understandable empty-history state', async () => {
    mockInsights.mockResolvedValue({ ...overall, points: [], qualifyingPaycheckCount: 0 });
    const view = await renderScreen();
    expect(await view.findByText('No Spending Bucket history')).toBeTruthy();
    expect(view.getByText(/paycheck-based history/)).toBeTruthy();
  });

  it('keeps a one-paycheck history readable', async () => {
    mockInsights.mockResolvedValue({
      ...overall,
      points: [overall.points[0]],
      qualifyingPaycheckCount: 1,
    });
    const view = await renderScreen();
    expect(await view.findByText('1 paycheck through 2026-07-15')).toBeTruthy();
    expect(view.getByLabelText('Budgeted versus spent trend graph')).toBeTruthy();
    expect(view.getByText('First paycheck')).toBeTruthy();
  });
});

async function renderScreen() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  function Wrapper({ children }: PropsWithChildren) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }
  return await render(<SpendingInsightsScreen />, { wrapper: Wrapper });
}
