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
        'First paycheck, Jul 1, 2026. Budgeted $50.00. Spent $40.00. Under by $10.00. 2 Spending Bucket entries included.',
      ),
    ).toBeTruthy();
    expect(view.getByText('2 Spending Bucket entries included')).toBeTruthy();
    expect(view.queryByText(/same-name bucket entries/)).toBeNull();
    expect(view.getByText(/trimmed names match exactly/)).toBeTruthy();
  });

  it('loads a selected bucket as sparse history without manufactured zero points', async () => {
    mockInsights.mockImplementation(async (name?: string) =>
      name === 'Gas'
        ? {
            ...overall,
            points: [{ ...overall.points[1], matchingBucketCount: 2 }],
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
    expect(view.getByText('2 same-name bucket entries combined')).toBeTruthy();
    expect(
      view.getByLabelText(
        'Second paycheck, Jul 8, 2026. Budgeted $50.00. Spent $55.00. Over by $5.00. 2 same-name bucket entries combined.',
      ),
    ).toBeTruthy();
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
    expect(view.getByText('2 Spending Bucket entries included')).toBeTruthy();
    expect(view.queryByText(/same-name bucket entries/)).toBeNull();
  });

  it('keeps cached overall history visible and retries a failed refresh once', async () => {
    const updated = { ...overall, asOfDate: '2026-07-16' };
    mockInsights.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(updated);
    const { view } = await renderScreenWithClient((client) => {
      client.setQueryData(['spending-buckets', 'insights', 'all'], overall);
    });
    expect(await view.findByText('Showing saved data. Reconnect to refresh.')).toBeTruthy();
    expect(view.getByText('First paycheck')).toBeTruthy();
    fireEvent.press(view.getByLabelText('Retry Spending Insights refresh'));
    expect(await view.findByText('3 paychecks through 2026-07-16')).toBeTruthy();
    expect(mockInsights).toHaveBeenCalledTimes(2);
  });

  it('retries cached selected history together with overall selector metadata', async () => {
    let selected = {
      ...overall,
      points: [{ ...overall.points[0], matchingBucketCount: 2 }],
      scope: 'BUCKET_NAME' as const,
      selectedBucketName: 'Gas',
    };
    mockInsights.mockImplementation(async (name?: string) => {
      if (name === 'Gas') {
        if (mockInsights.mock.calls.filter(([calledName]) => calledName === 'Gas').length === 1) {
          throw new Error('offline');
        }
        return selected;
      }
      return overall;
    });
    const { view } = await renderScreenWithClient((client) => {
      client.setQueryData(['spending-buckets', 'insights', 'all'], overall);
      client.setQueryData(['spending-buckets', 'insights', 'Gas'], selected);
    });
    await view.findByText('First paycheck');
    fireEvent.press(view.getByLabelText('Gas'));
    expect(await view.findByText('Showing saved data. Reconnect to refresh.')).toBeTruthy();
    expect(view.getByLabelText('Gas insight history')).toBeTruthy();
    selected = { ...selected, asOfDate: '2026-07-17' };
    fireEvent.press(view.getByLabelText('Retry Spending Insights refresh'));
    expect(await view.findByText('1 paycheck through 2026-07-17')).toBeTruthy();
    await waitFor(() => {
      expect(mockInsights.mock.calls.filter(([name]) => name === undefined)).toHaveLength(2);
      expect(mockInsights.mock.calls.filter(([name]) => name === 'Gas')).toHaveLength(2);
    });
  });

  it('selects a bucket literally named __all__ by its opaque option ID', async () => {
    const withSentinelName = { ...overall, availableBucketNames: ['__all__'] };
    mockInsights.mockImplementation(async (name?: string) =>
      name === '__all__'
        ? {
            ...withSentinelName,
            points: [{ ...overall.points[1], paycheckName: '__all__ bucket paycheck' }],
            scope: 'BUCKET_NAME',
            selectedBucketName: '__all__',
          }
        : withSentinelName,
    );
    const view = await renderScreen();
    await view.findByText('First paycheck');
    expect(view.getByTestId('segmented-Spending Insights bucket-scope:overall')).toBeTruthy();
    expect(view.getByTestId('segmented-Spending Insights bucket-bucket:0')).toBeTruthy();
    fireEvent.press(view.getByLabelText('__all__'));
    await waitFor(() => expect(mockInsights).toHaveBeenCalledWith('__all__'));
    expect(await view.findByLabelText('__all__ insight history')).toBeTruthy();
    expect(view.getByText('__all__ bucket paycheck')).toBeTruthy();
    expect(view.queryByText('First paycheck')).toBeNull();
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
  return (await renderScreenWithClient()).view;
}

async function renderScreenWithClient(seed?: (client: QueryClient) => void) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  seed?.(client);
  function Wrapper({ children }: PropsWithChildren) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }
  return { client, view: await render(<SpendingInsightsScreen />, { wrapper: Wrapper }) };
}
