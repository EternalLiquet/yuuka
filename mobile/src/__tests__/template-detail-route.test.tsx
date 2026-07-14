/* eslint-disable @typescript-eslint/no-require-imports */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';
import { Alert } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import type { BudgetTemplate, TemplateEntry } from '@/api/contracts';

import EditTemplateScreen from '../../app/templates/[id]';

jest.mock('react-native-draggable-flatlist', () => {
  const React = require('react');
  const { View } = require('react-native');
  const DraggableFlatList = React.forwardRef((props: any, ref: any) => {
    const data = props.data ?? [];
    const renderItem = props.renderItem;
    const keyExtractor = props.keyExtractor;
    const header =
      typeof props.ListHeaderComponent === 'function'
        ? React.createElement(props.ListHeaderComponent as React.ComponentType)
        : props.ListHeaderComponent;
    const empty =
      data.length === 0
        ? typeof props.ListEmptyComponent === 'function'
          ? React.createElement(props.ListEmptyComponent as React.ComponentType)
          : props.ListEmptyComponent
        : null;
    return React.createElement(
      View,
      { ref },
      header,
      empty,
      ...data.map((item: any) =>
        React.createElement(
          React.Fragment,
          { key: keyExtractor(item) },
          renderItem({ drag: jest.fn(), item }),
        ),
      ),
    );
  });
  DraggableFlatList.displayName = 'MockDraggableFlatList';
  return {
    __esModule: true,
    default: DraggableFlatList,
  };
});

const mockReplace = jest.fn();
const templateId = '11111111-1111-4111-8111-111111111201';
const mockApi = {
  addTemplateEntry: jest.fn(),
  archiveTemplate: jest.fn(),
  deleteTemplateEntry: jest.fn(),
  duplicateTemplate: jest.fn(),
  reorderTemplateEntries: jest.fn(),
  restoreTemplate: jest.fn(),
  template: jest.fn(),
  updateTemplate: jest.fn(),
  updateTemplateEntry: jest.fn(),
};

jest.mock('expo-router', () => {
  const React = require('react');
  const { View } = require('react-native');
  function Stack({ children }: { children?: unknown }) {
    return React.createElement(View, null, children);
  }
  Stack.Screen = function Screen() {
    return null;
  };
  return {
    Stack,
    useLocalSearchParams: () => ({ id: templateId }),
    useRouter: () => ({ replace: mockReplace }),
  };
});

jest.mock('@/api/use-yuuka-api', () => ({
  useYuukaApi: () => mockApi,
}));

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

jest.mock('@/components/yuuka-mascot', () => {
  const React = require('react');
  const { Text } = require('react-native');
  return {
    YuukaMascot: ({ testID }: { testID?: string }) =>
      React.createElement(Text, { testID }, 'Yuuka'),
  };
});

function wrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: PropsWithChildren) {
    return (
      <SafeAreaProvider
        initialMetrics={{
          frame: { height: 844, width: 390, x: 0, y: 0 },
          insets: { bottom: 0, left: 0, right: 0, top: 0 },
        }}
      >
        <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
      </SafeAreaProvider>
    );
  };
}

function client() {
  return new QueryClient({
    defaultOptions: {
      mutations: { gcTime: Infinity, retry: false },
      queries: { gcTime: Infinity, retry: false, staleTime: Infinity },
    },
  });
}

describe('Template detail route', () => {
  afterEach(() => {
    cleanup();
  });

  beforeEach(() => {
    jest.resetAllMocks();
    mockApi.template.mockResolvedValue(template());
    mockApi.addTemplateEntry.mockResolvedValue(
      entry({ id: '11111111-1111-4111-8111-111111111299' }),
    );
    mockApi.updateTemplate.mockResolvedValue(template({ name: 'Renamed' }));
    mockApi.updateTemplateEntry.mockResolvedValue(entry({ name: 'Updated rent' }));
    mockApi.deleteTemplateEntry.mockResolvedValue(undefined);
    mockApi.duplicateTemplate.mockResolvedValue(
      template({ id: '11111111-1111-4111-8111-111111111300', name: 'Rent 1 Copy' }),
    );
    mockApi.reorderTemplateEntries.mockResolvedValue(template());
    mockApi.restoreTemplate.mockResolvedValue(template());
    mockApi.archiveTemplate.mockResolvedValue(template({ archived: true }));
  });

  it('shows archived templates as read-only and restores with the current version', async () => {
    const archived = template({ archived: true, archivedAt: '2026-07-13T12:00:00Z', version: 9 });
    mockApi.template.mockResolvedValue(archived);
    const queryClient = client();
    queryClient.setQueryData(['template', templateId], archived);
    const view = await render(<EditTemplateScreen />, { wrapper: wrapper(queryClient) });
    await waitFor(() => expect(view.getByText(/Archived/)).toBeTruthy());

    expect(view.queryByLabelText('Add entry')).toBeNull();
    expect(view.queryByLabelText('Edit Rent')).toBeNull();
    await act(async () => {
      fireEvent.press(view.getByLabelText('Restore template'));
    });

    await waitFor(() => expect(mockApi.restoreTemplate).toHaveBeenCalledWith(templateId, 9));
    await waitFor(() =>
      expect(view.getByLabelText('Restore template').props.accessibilityState.busy).toBe(false),
    );
    queryClient.clear();
  });

  it('duplicates a template once and opens the copy', async () => {
    mockApi.duplicateTemplate.mockResolvedValue(
      template({ id: '11111111-1111-4111-8111-111111111300' }),
    );
    const queryClient = client();
    queryClient.setQueryData(['template', templateId], template());
    const view = await render(<EditTemplateScreen />, { wrapper: wrapper(queryClient) });
    await waitFor(() => expect(view.getByText('Rent 1')).toBeTruthy());

    await act(async () => {
      fireEvent.press(view.getByLabelText('Duplicate template'));
      fireEvent.press(view.getByLabelText('Duplicate template'));
    });
    await waitFor(() => expect(mockApi.duplicateTemplate).toHaveBeenCalledTimes(1));
    expect(mockApi.duplicateTemplate).toHaveBeenCalledWith(templateId);

    await waitFor(() =>
      expect(mockReplace).toHaveBeenCalledWith('/templates/11111111-1111-4111-8111-111111111300'),
    );
    queryClient.clear();
  });

  it('keeps stale lifecycle errors visible', async () => {
    mockApi.restoreTemplate.mockRejectedValue(
      new Error('This record changed since it was loaded.'),
    );
    const archived = template({ archived: true, archivedAt: '2026-07-13T12:00:00Z', version: 9 });
    mockApi.template.mockResolvedValue(archived);
    const queryClient = client();
    queryClient.setQueryData(['template', templateId], archived);
    const view = await render(<EditTemplateScreen />, { wrapper: wrapper(queryClient) });
    await waitFor(() => expect(view.getByText(/Archived/)).toBeTruthy());

    await act(async () => {
      fireEvent.press(view.getByLabelText('Restore template'));
    });

    await waitFor(() =>
      expect(view.getByText('This record changed since it was loaded.')).toBeTruthy(),
    );
    await waitFor(() =>
      expect(view.getByLabelText('Restore template').props.accessibilityState.busy).toBe(false),
    );
    queryClient.clear();
  });

  it('archives only after confirmation and guards duplicate archive actions', async () => {
    const alert = jest.spyOn(Alert, 'alert').mockImplementation(jest.fn());
    mockApi.archiveTemplate.mockResolvedValue(template({ archived: true }));
    const queryClient = client();
    queryClient.setQueryData(['template', templateId], template());
    const view = await render(<EditTemplateScreen />, { wrapper: wrapper(queryClient) });
    await waitFor(() => expect(view.getByText('Rent 1')).toBeTruthy());

    fireEvent.press(view.getByLabelText('Archive template'));
    expect(mockApi.archiveTemplate).not.toHaveBeenCalled();
    const actions = alert.mock.calls[0][2] as { onPress?: () => void; text: string }[];
    actions.find((action) => action.text === 'Cancel')?.onPress?.();
    expect(mockApi.archiveTemplate).not.toHaveBeenCalled();

    await act(async () => {
      actions.find((action) => action.text === 'Archive')?.onPress?.();
      actions.find((action) => action.text === 'Archive')?.onPress?.();
    });
    await waitFor(() => expect(mockApi.archiveTemplate).toHaveBeenCalledTimes(1));
    expect(mockApi.archiveTemplate).toHaveBeenCalledWith(templateId, 7);

    await waitFor(() => expect(mockApi.archiveTemplate).toHaveBeenCalledTimes(1));
    alert.mockRestore();
    queryClient.clear();
  });
});

function template(overrides: Partial<BudgetTemplate> = {}): BudgetTemplate {
  const entries = [
    entry({ id: '11111111-1111-4111-8111-111111111201', name: 'Rent', position: 0 }),
    entry({
      defaultAmountMinor: 10000,
      entryType: 'SPENDING_BUCKET',
      id: '11111111-1111-4111-8111-111111111202',
      name: 'Groceries',
      paymentMethod: null,
      position: 1,
    }),
  ];
  return {
    archived: false,
    archivedAt: null,
    createdAt: '2026-07-12T12:00:00Z',
    defaultTotalMinor: 120000,
    description: 'Repeat rent check',
    entries,
    entryCount: entries.length,
    id: templateId,
    name: 'Rent 1',
    updatedAt: '2026-07-13T12:00:00Z',
    version: 7,
    ...overrides,
  };
}

function entry(overrides: Partial<TemplateEntry> = {}): TemplateEntry {
  return {
    accountName: null,
    createdAt: '2026-07-12T12:00:00Z',
    defaultAmountMinor: 110000,
    defaultDueOffsetDays: null,
    entryType: 'BILL',
    id: '11111111-1111-4111-8111-111111111201',
    name: 'Rent',
    notes: null,
    payee: null,
    paymentMethod: 'MANUAL',
    position: 0,
    targetDate: null,
    targetMinor: null,
    updatedAt: '2026-07-13T12:00:00Z',
    version: 1,
    ...overrides,
  };
}
