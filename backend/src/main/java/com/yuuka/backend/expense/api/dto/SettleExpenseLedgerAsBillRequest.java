package com.yuuka.backend.expense.api.dto;

import com.yuuka.backend.paycheck.domain.EntryPaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

public record SettleExpenseLedgerAsBillRequest(
    @NotNull UUID paycheckId,
    @NotNull @PositiveOrZero Long ledgerVersion,
    @Size(max = 160) String name,
    EntryPaymentMethod paymentMethod,
    LocalDate dueDate,
    @Size(max = 160) String accountName,
    @Size(max = 160) String payee,
    @Size(max = 2000) String notes) {}
