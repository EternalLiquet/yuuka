import { fireEvent, render, waitFor } from '@testing-library/react-native';

import { EntryEditor } from '@/features/paychecks/entry-editor';

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

describe('EntryEditor autopay submit', () => {
  it('submits Autopay when the manual-payment checkbox is unchecked', async () => {
    const onSubmit = jest.fn().mockResolvedValue(undefined);
    const view = await render(
      <EntryEditor entry={null} onClose={jest.fn()} onSubmit={onSubmit} visible />,
    );

    fireEvent.changeText(await view.findByLabelText('Name'), 'Internet');
    fireEvent.changeText(await view.findByLabelText('Amount'), '80.00');
    await fireEvent.press(view.getByLabelText('Save entry'));

    await waitFor(() => expect(onSubmit).toHaveBeenCalled());
    expect(onSubmit.mock.calls[0][0]).toMatchObject({ paymentMethod: 'AUTOPAY' });
  });
});
