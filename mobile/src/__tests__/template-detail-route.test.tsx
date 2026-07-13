/* eslint-disable @typescript-eslint/no-require-imports */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import type { BudgetTemplate, TemplateEntry } from '@/api/contracts';
import { TemplateEntryEditor } from '@/features/templates/template-entry-editor';

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
  reorderTemplateEntries: jest.fn(),
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
      queries: { gcTime: Infinity, retry: false },
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
    mockApi.reorderTemplateEntries.mockResolvedValue(template());
    mockApi.archiveTemplate.mockResolvedValue(template({ archived: true }));
  });

  it('adds a Manual Pay bill template entry', async () => {
    const queryClient = client();
    queryClient.setQueryData(['template', templateId], template());
    const view = await render(<EditTemplateScreen />, { wrapper: wrapper(queryClient) });
    await waitFor(() => expect(view.getByText('Rent 1')).toBeTruthy());

    await fireEvent.press(view.getByLabelText('Add entry'));
    fireEvent.changeText(await view.findByLabelText('Name'), 'Internet');
    fireEvent.changeText(await view.findByLabelText('Amount'), '89.99');
    await fireEvent.press(view.getByLabelText('I need to pay this manually'));
    await fireEvent.press(view.getByLabelText('Save template entry'));

    await waitFor(() => expect(mockApi.addTemplateEntry).toHaveBeenCalled());
    expect(mockApi.addTemplateEntry).toHaveBeenCalledWith(
      templateId,
      expect.objectContaining({
        defaultAmountMinor: 8999,
        entryType: 'BILL',
        name: 'Internet',
        paymentMethod: 'MANUAL',
      }),
    );
    queryClient.clear();
  });

  it('edits and deletes template entries through the editor controls', async () => {
    const onSubmit = jest.fn().mockResolvedValue(undefined);
    const onDelete = jest.fn().mockResolvedValue(undefined);
    const view = await render(
      <TemplateEntryEditor
        entry={entry()}
        onClose={jest.fn()}
        onDelete={onDelete}
        onSubmit={onSubmit}
        visible
      />,
    );

    fireEvent.changeText(await view.findByLabelText('Name'), 'Rent Updated');
    fireEvent.changeText(await view.findByLabelText('Amount'), '1150.00');
    await fireEvent.press(view.getByLabelText('Save template entry'));

    await waitFor(() => expect(onSubmit).toHaveBeenCalled());
    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({
        defaultAmountMinor: 115000,
        name: 'Rent Updated',
        paymentMethod: 'MANUAL',
      }),
    );

    await fireEvent.press(view.getByLabelText('Delete template entry'));
    await waitFor(() => expect(onDelete).toHaveBeenCalled());
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
