/* eslint-disable @typescript-eslint/no-require-imports */
import { render } from '@testing-library/react-native';
import { existsSync } from 'node:fs';
import { resolve } from 'node:path';

import AuthLayout from '../../app/(auth)/_layout';
import IndexScreen from '../../app/index';
import SinkingFundsCompatibilityRoute from '../../app/sinking-funds';

let mockAuthenticated = true;

jest.mock('expo-router', () => {
  const React = require('react');
  const { View } = require('react-native');
  function Redirect({ href }: { href: string }) {
    return React.createElement(View, { accessibilityLabel: `Redirect ${href}` });
  }
  function Stack() {
    return React.createElement(View, { accessibilityLabel: 'Auth stack' });
  }
  return { Redirect, Stack };
});

jest.mock('@/auth/auth-provider', () => ({
  useAuth: () => ({
    isLoading: false,
    session: mockAuthenticated ? { accessToken: 'token' } : null,
  }),
}));

describe('Home navigation compatibility', () => {
  beforeEach(() => {
    mockAuthenticated = true;
  });

  it('opens Home from both authenticated entry points', async () => {
    const index = await render(<IndexScreen />);
    const auth = await render(<AuthLayout />);
    expect(index.getByLabelText('Redirect /(tabs)/home')).toBeTruthy();
    expect(auth.getByLabelText('Redirect /(tabs)/home')).toBeTruthy();
  });

  it('keeps /sinking-funds compatible while selecting the Planned Savings tab', async () => {
    const view = await render(<SinkingFundsCompatibilityRoute />);
    expect(view.getByLabelText('Redirect /(tabs)/planned-savings')).toBeTruthy();
  });

  it('preserves existing detail deep-link route files', () => {
    const app = resolve(__dirname, '../../app');
    expect(existsSync(resolve(app, 'paychecks/[id].tsx'))).toBe(true);
    expect(existsSync(resolve(app, 'paybacks/[id].tsx'))).toBe(true);
    expect(existsSync(resolve(app, 'sinking-funds/[id].tsx'))).toBe(true);
    expect(existsSync(resolve(app, 'expense-ledgers/[id].tsx'))).toBe(true);
    expect(existsSync(resolve(app, 'recurring-bills/index.tsx'))).toBe(true);
  });
});
