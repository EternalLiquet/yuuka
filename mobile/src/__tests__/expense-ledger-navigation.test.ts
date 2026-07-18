import type { ExpenseLedgerSettlement } from '@/api/contracts';
import { expenseLedgerSettlementTargetPath } from '@/features/expense-ledgers/navigation';

describe('Expense Ledger settlement navigation', () => {
  it('opens a Bill settlement through its containing paycheck', () => {
    expect(
      expenseLedgerSettlementTargetPath(
        settlement({
          settlementType: 'BILL',
          targetId: '11111111-1111-4111-8111-111111111201',
          targetPaycheckId: '11111111-1111-4111-8111-111111111202',
        }),
      ),
    ).toBe('/paychecks/11111111-1111-4111-8111-111111111202');
  });

  it('keeps Payback settlement navigation on the Payback target', () => {
    expect(
      expenseLedgerSettlementTargetPath(
        settlement({
          settlementType: 'PAYBACK',
          targetId: '11111111-1111-4111-8111-111111111203',
          targetPaycheckId: null,
        }),
      ),
    ).toBe('/paybacks/11111111-1111-4111-8111-111111111203');
  });
});

function settlement(
  overrides: Pick<ExpenseLedgerSettlement, 'settlementType' | 'targetId' | 'targetPaycheckId'>,
): ExpenseLedgerSettlement {
  return {
    createdAt: '2026-07-18T12:00:00Z',
    id: '11111111-1111-4111-8111-111111111200',
    ledgerId: '11111111-1111-4111-8111-111111111199',
    settledAt: '2026-07-18T12:00:00Z',
    settlementAmountMinor: 5000,
    ...overrides,
  };
}
