import { fireEvent, render } from '@testing-library/react-native';

import { AppMenuButton } from '@/components/app-menu';

const mockReplace = jest.fn();
jest.mock('expo-router', () => ({ useRouter: () => ({ replace: mockReplace }) }));
jest.mock('@/theme/use-app-theme', () => ({
  useAppTheme: () => ({
    colors: { accent: '#ff8', border: '#333', surface: '#111', text: '#fff' },
  }),
}));

describe('AppMenuButton', () => {
  beforeEach(() => mockReplace.mockClear());

  it('opens, exposes every top-level destination, navigates, and closes', async () => {
    const view = await render(<AppMenuButton />);
    await fireEvent.press(view.getByLabelText('Open app menu'));

    expect(view.getByLabelText('Open Home')).toBeTruthy();
    expect(view.getByLabelText('Open Active')).toBeTruthy();
    expect(view.getByLabelText('Open Expense Lists')).toBeTruthy();
    expect(view.getByLabelText('Open History')).toBeTruthy();
    expect(view.getByLabelText('Open Paybacks')).toBeTruthy();
    expect(view.getByLabelText('Open Planned Savings')).toBeTruthy();
    expect(view.getByLabelText('Open Templates')).toBeTruthy();
    expect(view.getByLabelText('Open Recurring Bills')).toBeTruthy();
    expect(view.getByLabelText('Open Settings')).toBeTruthy();

    await fireEvent.press(view.getByLabelText('Open Recurring Bills'));
    expect(mockReplace).toHaveBeenCalledWith('/recurring-bills');
    expect(view.queryByLabelText('Close app menu')).toBeNull();
  });

  it('opens Expense Lists from the app menu', async () => {
    const view = await render(<AppMenuButton />);
    await fireEvent.press(view.getByLabelText('Open app menu'));
    await fireEvent.press(view.getByLabelText('Open Expense Lists'));

    expect(mockReplace).toHaveBeenCalledWith('/(tabs)/expense-ledgers');
    expect(view.queryByLabelText('Close app menu')).toBeNull();
  });

  it('closes without navigating', async () => {
    const view = await render(<AppMenuButton />);
    await fireEvent.press(view.getByLabelText('Open app menu'));
    await fireEvent.press(view.getByLabelText('Close app menu'));
    expect(mockReplace).not.toHaveBeenCalled();
  });

  it('replaces top-level destinations instead of building duplicate menu stacks', async () => {
    const view = await render(<AppMenuButton />);
    await fireEvent.press(view.getByLabelText('Open app menu'));
    await fireEvent.press(view.getByLabelText('Open Home'));

    expect(mockReplace).toHaveBeenCalledWith('/(tabs)/home');
  });
});
