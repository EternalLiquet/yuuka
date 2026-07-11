import { fireEvent, render } from '@testing-library/react-native';

import { SegmentedControl } from '@/components/segmented-control';
import { ErrorState, StaleBanner } from '@/components/states';
import { StatusBadge } from '@/components/status-badge';

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

describe('shared component behavior', () => {
  it('exposes retry states accessibly', async () => {
    const retry = jest.fn();
    const retryView = await render(<ErrorState message="Network unavailable" retry={retry} />);

    fireEvent.press(retryView.getByLabelText('Retry'));
    expect(retry).toHaveBeenCalledTimes(1);
    expect(retryView.getByText('Network unavailable')).toBeTruthy();
  });

  it('exposes stale-data state accessibly', async () => {
    const staleView = await render(<StaleBanner />);
    expect(staleView.getByText('Showing saved data. Reconnect to refresh.')).toBeTruthy();
  });

  it.each([
    ['NOT_PAID', 'Status: Not Paid'],
    ['PROCESSING', 'Status: Processing'],
    ['POSTED', 'Status: Posted'],
  ] as const)('maps %s to visible text and an accessibility label', async (status, label) => {
    const view = await render(<StatusBadge status={status} />);
    expect(view.getByLabelText(label)).toBeTruthy();
  });

  it('emits filter changes from a labeled segmented control', async () => {
    const onChange = jest.fn();
    const view = await render(
      <SegmentedControl
        label="Status filter"
        onChange={onChange}
        options={[
          { label: 'All', value: 'ALL' },
          { label: 'Posted', value: 'POSTED' },
        ]}
        value="ALL"
      />,
    );

    fireEvent.press(view.getByLabelText('Posted'));
    expect(onChange).toHaveBeenCalledWith('POSTED');
  });
});
