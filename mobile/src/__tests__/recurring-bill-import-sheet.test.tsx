/* eslint-disable @typescript-eslint/no-require-imports */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { ComponentProps, PropsWithChildren } from 'react';

import type { RecurringBill, RecurringBillOccurrence } from '@/api/contracts';
import { ImportRecurringBillsSheet } from '@/features/recurring-bills/import-recurring-bills-sheet';

const mockApi = {
  me: jest.fn(),
  recurringBills: jest.fn(),
  recurringBillTimeline: jest.fn(),
  updateRecurringBill: jest.fn(),
};

jest.mock('@/api/use-yuuka-api', () => ({ useYuukaApi: () => mockApi }));

jest.mock('@/settings/settings-provider', () => ({
  useSettings: () => ({
    settings: {
      currencyCode: 'USD',
      recurringBillSuggestionDays: 7,
      theme: 'dark',
    },
  }),
}));

jest.mock('@/components/yuuka-mascot', () => {
  const React = require('react');
  const { Text } = require('react-native');
  return { YuukaMascot: () => React.createElement(Text, null, 'Yuuka') };
});

function client() {
  return new QueryClient({
    defaultOptions: {
      mutations: { gcTime: Infinity, retry: false },
      queries: { gcTime: Infinity, retry: false, staleTime: 0 },
    },
  });
}

function wrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: PropsWithChildren) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  };
}

async function sheet(
  queryClient: QueryClient,
  overrides: Partial<ComponentProps<typeof ImportRecurringBillsSheet>> = {},
) {
  return await render(
    <ImportRecurringBillsSheet
      incomeDate="2026-07-17"
      onClose={jest.fn()}
      onImport={jest.fn().mockResolvedValue(undefined)}
      visible
      {...overrides}
    />,
    { wrapper: wrapper(queryClient) },
  );
}

describe('Recurring Bill import sheet query states', () => {
  afterEach(async () => {
    await cleanup();
  });

  beforeEach(() => {
    jest.resetAllMocks();
    mockApi.me.mockResolvedValue({ recurringBillSuggestionDays: 7 });
    mockApi.recurringBills.mockResolvedValue({ items: [definition] });
    mockApi.recurringBillTimeline.mockResolvedValue(timeline);
  });

  it('renders loading while required data is pending', async () => {
    mockApi.recurringBills.mockReturnValue(new Promise(() => undefined));
    mockApi.recurringBillTimeline.mockReturnValue(new Promise(() => undefined));
    const queryClient = client();
    const view = await sheet(queryClient);

    expect(view.getByLabelText('Loading recurring Bills...')).toBeTruthy();
  });

  it('renders the picker after both required queries succeed', async () => {
    const queryClient = client();
    const view = await sheet(queryClient);

    expect(await view.findAllByText('Electric')).not.toHaveLength(0);
    expect(view.getByText('All recurring Bills')).toBeTruthy();
  });

  it('renders a blocking error when definitions fail without cached data', async () => {
    mockApi.recurringBills.mockRejectedValue(new Error('Definitions unavailable'));
    const queryClient = client();
    const view = await sheet(queryClient);

    expect(await view.findByText('Could not load')).toBeTruthy();
    expect(view.getByText('Definitions unavailable')).toBeTruthy();
    expect(view.queryByText('All recurring Bills')).toBeNull();
  });

  it('renders a blocking error when the timeline fails without cached data', async () => {
    mockApi.recurringBillTimeline.mockRejectedValue(new Error('Timeline unavailable'));
    const queryClient = client();
    const view = await sheet(queryClient);

    expect(await view.findByText('Could not load')).toBeTruthy();
    expect(view.getByText('Timeline unavailable')).toBeTruthy();
    expect(view.queryByText('All recurring Bills')).toBeNull();
  });

  it('keeps the picker visible with a warning when definitions refresh fails', async () => {
    mockApi.recurringBills.mockRejectedValue(new Error('Definitions unavailable'));
    const queryClient = client();
    queryClient.setQueryData(['recurring-bills', 'definitions', 'active'], {
      items: [definition],
    });
    const view = await sheet(queryClient);

    expect(await view.findByText('Showing saved recurring Bill data')).toBeTruthy();
    expect(view.getAllByText('Electric')).not.toHaveLength(0);
    expect(view.getByText('All recurring Bills')).toBeTruthy();
  });

  it('keeps the picker visible with a warning when the timeline refresh fails', async () => {
    mockApi.recurringBillTimeline.mockRejectedValue(new Error('Timeline unavailable'));
    const queryClient = client();
    queryClient.setQueryData(['recurring-bills', 'import-options', '2026-07-17'], timeline);
    const view = await sheet(queryClient);

    expect(await view.findByText('Showing saved recurring Bill data')).toBeTruthy();
    expect(view.getAllByText('Electric')).not.toHaveLength(0);
    expect(view.getByText('All recurring Bills')).toBeTruthy();
  });

  it('retries every failed required query and clears the stale warning on success', async () => {
    mockApi.recurringBills
      .mockRejectedValueOnce(new Error('Definitions unavailable'))
      .mockResolvedValue({ items: [definition] });
    mockApi.recurringBillTimeline
      .mockRejectedValueOnce(new Error('Timeline unavailable'))
      .mockResolvedValue(timeline);
    const queryClient = client();
    queryClient.setQueryData(['recurring-bills', 'definitions', 'active'], {
      items: [definition],
    });
    queryClient.setQueryData(['recurring-bills', 'import-options', '2026-07-17'], timeline);
    const view = await sheet(queryClient);
    expect(await view.findByText('Showing saved recurring Bill data')).toBeTruthy();

    await act(async () => {
      fireEvent.press(view.getByLabelText('Retry recurring Bill data'));
    });

    await waitFor(() => expect(mockApi.recurringBills).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(mockApi.recurringBillTimeline).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(view.queryByText('Showing saved recurring Bill data')).toBeNull());
  });

  it('does not add local draft entries when the typical-amount update fails', async () => {
    const onImport = jest.fn().mockResolvedValue(undefined);
    mockApi.updateRecurringBill.mockRejectedValue(new Error('Typical amount conflict'));
    const queryClient = client();
    const view = await sheet(queryClient, { localDraft: true, onImport });
    expect(await view.findAllByText('Electric')).not.toHaveLength(0);

    await act(async () => {
      fireEvent.press(view.getAllByLabelText('Edit amount for Electric')[0]);
    });
    await act(async () => {
      fireEvent.changeText(await view.findByLabelText('Amount for this paycheck'), '146.00');
    });
    await act(async () => {
      fireEvent.press(view.getByLabelText('Update typical amount'));
    });
    const addSelected = await view.findByLabelText('Add selected Bills (1)');
    await act(async () => {
      fireEvent.press(addSelected);
    });
    expect(await view.findByText('Typical amount conflict')).toBeTruthy();
    expect(mockApi.updateRecurringBill).toHaveBeenCalledTimes(1);
    expect(onImport).not.toHaveBeenCalled();
    expect(view.getByText('Import recurring Bills')).toBeTruthy();
  });
});

const definition: RecurringBill = {
  accountName: 'Checking',
  active: true,
  createdAt: '2026-07-01T12:00:00Z',
  dueDay: 21,
  id: '11111111-1111-4111-8111-111111111111',
  name: 'Electric',
  notes: null,
  payee: 'Power Co',
  paymentMethod: 'AUTOPAY',
  recurrenceType: 'MONTHLY',
  typicalAmountMinor: 12000,
  updatedAt: '2026-07-01T12:00:00Z',
  version: 2,
};

const occurrence: RecurringBillOccurrence = {
  accountName: 'Checking',
  definitionId: definition.id,
  definitionVersion: definition.version,
  importCount: 0,
  imports: [],
  name: definition.name,
  notes: null,
  occurrenceDate: '2026-07-21',
  payee: definition.payee,
  paymentMethod: definition.paymentMethod,
  typicalAmountMinor: definition.typicalAmountMinor,
};

const timeline = {
  from: '2026-06-16',
  items: [occurrence],
  through: '2026-09-17',
};
