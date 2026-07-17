import type { RecurringBillOccurrence } from '@/api/contracts';
import {
  recurringImportSelection,
  updateRecurringAmountSelection,
} from '@/features/recurring-bills/import-recurring-bills-sheet';
import {
  applicationEntriesFromDraft,
  draftEntriesFromRecurringBills,
} from '@/features/templates/application-draft';

const occurrence: RecurringBillOccurrence = {
  accountName: 'Power',
  definitionId: '11111111-1111-4111-8111-111111111111',
  definitionVersion: 2,
  importCount: 0,
  imports: [],
  name: 'Electric',
  notes: 'Variable charge',
  occurrenceDate: '2026-07-21',
  payee: 'Power Co',
  paymentMethod: 'AUTOPAY',
  typicalAmountMinor: 12000,
};

describe('recurring Bill import choices', () => {
  it('uses the typical amount for a normal selection', () => {
    expect(recurringImportSelection(occurrence)).toEqual(
      expect.objectContaining({ amountMinor: 12000, updateTypicalAmount: false }),
    );
  });

  it('distinguishes one-paycheck overrides from explicit typical-amount updates', () => {
    expect(recurringImportSelection(occurrence, 14600, false)).toEqual(
      expect.objectContaining({ amountMinor: 14600, updateTypicalAmount: false }),
    );
    expect(recurringImportSelection(occurrence, 14600, true)).toEqual(
      expect.objectContaining({ amountMinor: 14600, updateTypicalAmount: true }),
    );
  });

  it('turns a selection into an editable ordinary Bill snapshot with provenance', () => {
    const draft = draftEntriesFromRecurringBills([
      recurringImportSelection(occurrence, 14600, false),
    ]);
    expect(draft[0]).toEqual(
      expect.objectContaining({
        amountMinor: 14600,
        entryType: 'BILL',
        name: 'Electric',
        paymentMethod: 'AUTOPAY',
        sourceRecurringBillDefinitionId: occurrence.definitionId,
        sourceRecurringOccurrenceDate: '2026-07-21',
      }),
    );
    expect(applicationEntriesFromDraft('2026-07-14', draft)[0]).toEqual(
      expect.objectContaining({
        dueDate: '2026-07-21',
        sourceRecurringBillDefinitionId: occurrence.definitionId,
      }),
    );
  });

  it('lets an edited due offset override the occurrence date without losing provenance', () => {
    const [draft] = draftEntriesFromRecurringBills([recurringImportSelection(occurrence)]);
    draft.defaultDueOffsetDays = 3;

    expect(applicationEntriesFromDraft('2026-07-14', [draft])[0]).toEqual(
      expect.objectContaining({
        dueDate: '2026-07-17',
        sourceRecurringBillDefinitionId: occurrence.definitionId,
        sourceRecurringOccurrenceDate: occurrence.occurrenceDate,
      }),
    );
  });

  it('keeps at most one typical-amount update for each definition', () => {
    const laterOccurrence = { ...occurrence, occurrenceDate: '2026-08-21' };
    let selected = updateRecurringAmountSelection({}, occurrence, 14600, true, false);
    selected = updateRecurringAmountSelection(selected, laterOccurrence, 15100, true, false);

    expect(Object.values(selected)).toEqual([
      expect.objectContaining({ occurrenceDate: '2026-07-21', updateTypicalAmount: false }),
      expect.objectContaining({ occurrenceDate: '2026-08-21', updateTypicalAmount: true }),
    ]);
  });

  it('limits local-draft imports to one typical-amount update across the action', () => {
    const otherDefinition = {
      ...occurrence,
      definitionId: '22222222-2222-4222-8222-222222222222',
      name: 'Internet',
    };
    let selected = updateRecurringAmountSelection({}, occurrence, 14600, true, true);
    selected = updateRecurringAmountSelection(selected, otherDefinition, 8100, true, true);

    expect(Object.values(selected)).toEqual([
      expect.objectContaining({ name: 'Electric', updateTypicalAmount: false }),
      expect.objectContaining({ name: 'Internet', updateTypicalAmount: true }),
    ]);
  });
});
