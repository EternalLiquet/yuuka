import { ExpenseLedgerSettlement } from '@/api/contracts';

export function expenseLedgerSettlementTargetPath(
  settlement: ExpenseLedgerSettlement,
): `/paychecks/${string}` | `/paybacks/${string}` | null {
  if (settlement.settlementType === 'BILL') {
    return settlement.targetPaycheckId ? `/paychecks/${settlement.targetPaycheckId}` : null;
  }
  return `/paybacks/${settlement.targetId}`;
}
