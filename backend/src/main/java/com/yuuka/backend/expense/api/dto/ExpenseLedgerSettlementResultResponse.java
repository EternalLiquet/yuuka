package com.yuuka.backend.expense.api.dto;

import com.yuuka.backend.payback.api.dto.PaybackResponse;
import com.yuuka.backend.paycheck.api.dto.EntryResponse;

public record ExpenseLedgerSettlementResultResponse(
    ExpenseLedgerResponse ledger, EntryResponse billEntry, PaybackResponse payback) {}
