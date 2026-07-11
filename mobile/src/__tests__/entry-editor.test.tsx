import { act, fireEvent, render, waitFor } from '@testing-library/react-native';

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

describe('EntryEditor', () => {
  it('preserves entry form input when a save fails', async () => {
    const onSubmit = jest.fn().mockRejectedValue(new Error('Server unavailable'));
    const view = await render(
      <EntryEditor entry={null} onClose={jest.fn()} onSubmit={onSubmit} visible />,
    );

    const name = await view.findByLabelText('Name');
    const amount = await view.findByLabelText('Amount');
    const notes = await view.findByLabelText('Notes (optional)');

    fireEvent.changeText(name, 'Groceries');
    fireEvent.changeText(amount, '150.00');
    fireEvent.changeText(notes, 'Weekly food');
    await act(async () => {
      fireEvent.press(view.getByLabelText('Save entry'));
    });

    await waitFor(() => expect(view.getByText('Server unavailable')).toBeTruthy());
    expect(view.getByLabelText('Name').props.value).toBe('Groceries');
    expect(view.getByLabelText('Amount').props.value).toBe('150.00');
    expect(view.getByLabelText('Notes (optional)').props.value).toBe('Weekly food');
  });
});
