import { fireEvent, render, waitFor } from '@testing-library/react-native';
import { Alert } from 'react-native';

import type { TemplateEntry } from '@/api/contracts';
import { TemplateEntryEditor } from '@/features/templates/template-entry-editor';

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

describe('TemplateEntryEditor deletion', () => {
  afterEach(() => {
    jest.restoreAllMocks();
  });

  it('confirms deletion, allows cancel, and calls delete once on rapid confirm', async () => {
    const alert = jest.spyOn(Alert, 'alert').mockImplementation(jest.fn());
    const onClose = jest.fn();
    const onDelete = jest.fn().mockResolvedValue(undefined);
    const view = await render(
      <TemplateEntryEditor
        entry={entry()}
        onClose={onClose}
        onDelete={onDelete}
        onSubmit={jest.fn()}
        visible
      />,
    );

    fireEvent.press(view.getByLabelText('Delete template entry'));
    expect(onDelete).not.toHaveBeenCalled();
    expect(alert).toHaveBeenCalledWith(
      'Delete template entry?',
      expect.stringContaining('Existing paychecks created from this template will not change.'),
      expect.any(Array),
    );

    const actions = alert.mock.calls[0][2] as { onPress?: () => void; text: string }[];
    actions.find((action) => action.text === 'Cancel')?.onPress?.();
    expect(onDelete).not.toHaveBeenCalled();

    actions.find((action) => action.text === 'Delete')?.onPress?.();
    actions.find((action) => action.text === 'Delete')?.onPress?.();

    await waitFor(() => expect(onDelete).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
  });

  it('keeps the editor open and shows API errors when deletion fails', async () => {
    const alert = jest.spyOn(Alert, 'alert').mockImplementation(jest.fn());
    const onClose = jest.fn();
    const onDelete = jest.fn().mockRejectedValue(new Error('Stale version'));
    const view = await render(
      <TemplateEntryEditor
        entry={entry()}
        onClose={onClose}
        onDelete={onDelete}
        onSubmit={jest.fn()}
        visible
      />,
    );

    fireEvent.press(view.getByLabelText('Delete template entry'));
    const actions = alert.mock.calls[0][2] as { onPress?: () => void; text: string }[];
    actions.find((action) => action.text === 'Delete')?.onPress?.();

    await waitFor(() => expect(view.getByText('Stale version')).toBeTruthy());
    expect(onClose).not.toHaveBeenCalled();
  });

  it('submits edited Manual Pay bill values', async () => {
    const onSubmit = jest.fn().mockResolvedValue(undefined);
    const view = await render(
      <TemplateEntryEditor entry={entry()} onClose={jest.fn()} onSubmit={onSubmit} visible />,
    );

    fireEvent.changeText(await view.findByLabelText('Name'), 'Rent Updated');
    fireEvent.changeText(await view.findByLabelText('Amount'), '1150.00');
    fireEvent.press(view.getByLabelText('Save template entry'));

    await waitFor(() => expect(onSubmit).toHaveBeenCalled());
    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({
        defaultAmountMinor: 115000,
        name: 'Rent Updated',
        paymentMethod: 'MANUAL',
      }),
    );
  });
});

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
