/* eslint-disable @typescript-eslint/no-require-imports */
import { act, fireEvent, render } from '@testing-library/react-native';

import { AuthRecoveryScreen } from '@/components/auth-recovery';

import AuthLayout from '../../app/(auth)/_layout';
import IndexScreen from '../../app/index';
import TabLayout from '../../app/(tabs)/_layout';

type MockAuth = {
  isLoading: boolean;
  recovery: { pending: boolean; reason: 'network' | 'server' | 'storage' } | null;
  retryRecovery: jest.Mock;
  session: { accessToken: string } | null;
  signOut: jest.Mock;
  status: string;
};

let mockAuth: MockAuth;

jest.mock('@/auth/auth-provider', () => ({ useAuth: () => mockAuth }));
jest.mock('@/components/app-menu', () => ({ AppMenuButton: () => null }));
jest.mock('@/settings/settings-provider', () => ({
  useSettings: () => ({ settings: { theme: 'dark' } }),
}));
jest.mock('expo-router', () => {
  const React = require('react');
  const { Text, View } = require('react-native');
  function Tabs() {
    return React.createElement(View, { testID: 'protected-tabs' });
  }
  Tabs.Screen = function Screen() {
    return null;
  };
  function Stack() {
    return React.createElement(View, { testID: 'sign-in-stack' });
  }
  function Redirect({ href }: { href: string }) {
    return React.createElement(Text, { testID: 'redirect' }, href);
  }
  return { Redirect, Stack, Tabs };
});

const NETWORK =
  'Cannot connect to Yuuka. Turn on Tailscale, then return here and tap Retry. Your saved sign-in has not been removed.';
const GENERIC =
  "Yuuka couldn't confirm your sign-in right now. Your saved sign-in has not been removed. Try again, or sign out.";

function recovering(
  reason: 'network' | 'server' | 'storage',
  pending = false,
  overrides: Partial<MockAuth> = {},
): MockAuth {
  return {
    isLoading: false,
    recovery: { pending, reason },
    retryRecovery: jest.fn(),
    session: null,
    signOut: jest.fn().mockResolvedValue(undefined),
    status: 'recovering',
    ...overrides,
  };
}

describe('auth recovery screen', () => {
  it('explains connectivity failures and offers Retry and Sign out', async () => {
    mockAuth = recovering('network');
    const view = await render(<AuthRecoveryScreen />);

    expect(view.getByText(NETWORK)).toBeTruthy();
    expect(view.queryByTestId('recovery-alert-icon')).toBeNull();
    const retry = view.getByRole('button', { name: 'Retry' });
    expect(retry.props.accessibilityState).toMatchObject({ busy: false, disabled: false });
    await fireEvent.press(retry);
    expect(mockAuth.retryRecovery).toHaveBeenCalledTimes(1);

    await fireEvent.press(view.getByRole('button', { name: 'Sign out' }));
    expect(mockAuth.signOut).toHaveBeenCalledTimes(1);
  });

  it.each(['server', 'storage'] as const)(
    'uses generic wording for %s problems, not the Tailscale wording',
    async (reason) => {
      mockAuth = recovering(reason);
      const view = await render(<AuthRecoveryScreen />);

      expect(view.getByText(GENERIC)).toBeTruthy();
      expect(view.queryByText(/Tailscale/)).toBeNull();
      expect(view.queryByText('Cannot connect to Yuuka')).toBeNull();
      expect(view.getByText('Sign-in not confirmed')).toBeTruthy();
      expect(view.getByTestId('recovery-alert-icon')).toBeTruthy();
    },
  );

  it('shows a busy, disabled Retry while a retry is pending', async () => {
    mockAuth = recovering('network', true);
    const view = await render(<AuthRecoveryScreen />);

    const retry = view.getByRole('button', { name: 'Retry' });
    expect(retry.props.accessibilityState).toMatchObject({ busy: true, disabled: true });
    await fireEvent.press(retry);
    expect(mockAuth.retryRecovery).not.toHaveBeenCalled();
  });

  it('prevents duplicate sign-out taps and survives a clearing failure', async () => {
    let reject: (error: Error) => void = () => undefined;
    mockAuth = recovering('network');
    mockAuth.signOut.mockImplementation(() => new Promise<void>((_resolve, rej) => (reject = rej)));
    const view = await render(<AuthRecoveryScreen />);

    const signOut = view.getByRole('button', { name: 'Sign out' });
    await act(async () => {
      signOut.props.onClick();
      signOut.props.onClick();
    });
    expect(mockAuth.signOut).toHaveBeenCalledTimes(1);
    await act(async () => reject(new Error('storage')));
    expect(view.getByRole('button', { name: 'Sign out' }).props.accessibilityState).toMatchObject({
      disabled: false,
    });
  });
});

describe('route gates while recovering', () => {
  it('renders recovery instead of protected tabs, sign-in, or redirects', async () => {
    mockAuth = recovering('network');
    for (const Gate of [TabLayout, AuthLayout, IndexScreen]) {
      const view = await render(<Gate />);
      expect(view.getByText(NETWORK)).toBeTruthy();
      expect(view.queryByTestId('protected-tabs')).toBeNull();
      expect(view.queryByTestId('sign-in-stack')).toBeNull();
      expect(view.queryByTestId('redirect')).toBeNull();
      await view.unmount();
    }
  });

  it('keeps existing gating for signed-in and signed-out states', async () => {
    mockAuth = recovering('network', false, {
      recovery: null,
      session: { accessToken: 'token' },
      status: 'authenticated',
    });
    const tabs = await render(<TabLayout />);
    expect(tabs.getByTestId('protected-tabs')).toBeTruthy();
    await tabs.unmount();

    mockAuth = recovering('network', false, { recovery: null, status: 'signedOut' });
    const auth = await render(<AuthLayout />);
    expect(auth.getByTestId('sign-in-stack')).toBeTruthy();
    await auth.unmount();
    const index = await render(<IndexScreen />);
    expect(index.getByText('/(auth)/sign-in')).toBeTruthy();
  });
});
