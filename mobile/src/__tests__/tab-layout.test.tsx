/* eslint-disable @typescript-eslint/no-require-imports */
import { render } from '@testing-library/react-native';

import TabLayout from '../../app/(tabs)/_layout';

type MockTabScreen = {
  name: string;
  options: {
    href?: null;
    title?: string;
  };
};

const mockTabScreens: MockTabScreen[] = [];

jest.mock('expo-router', () => {
  const React = require('react');
  const { View } = require('react-native');
  const Tabs = ({ children }: { children: React.ReactNode }) =>
    React.createElement(View, null, children);
  function MockTabsScreen(props: MockTabScreen) {
    mockTabScreens.push(props);
    return null;
  }
  Tabs.Screen = MockTabsScreen;
  return { Redirect: () => null, Tabs };
});

jest.mock('@/auth/auth-provider', () => ({
  useAuth: () => ({ isLoading: false, session: { accessToken: 'token' } }),
}));

jest.mock('@/components/app-menu', () => ({ AppMenuButton: () => null }));

jest.mock('@/theme/use-app-theme', () => ({
  useAppTheme: () => ({
    colors: {
      accent: '#ff8',
      background: '#000',
      border: '#333',
      muted: '#999',
      surface: '#111',
      text: '#fff',
    },
  }),
}));

describe('bottom tab layout', () => {
  beforeEach(() => mockTabScreens.splice(0));

  it('shows Active, Paybacks, and History while keeping Expense Lists off the bottom bar', async () => {
    const view = await render(<TabLayout />);

    expect(
      mockTabScreens.filter((screen) => screen.options.href !== null).map((screen) => screen.name),
    ).toEqual(['active', 'paybacks', 'history']);
    expect(
      mockTabScreens.find((screen) => screen.name === 'expense-ledgers')?.options,
    ).toMatchObject({
      href: null,
      title: 'Expense Lists',
    });

    await view.unmount();
  });
});
