CREATE TABLE expense_ledgers (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES user_accounts(id),
    name VARCHAR(160) NOT NULL,
    notes VARCHAR(2000),
    state VARCHAR(20) NOT NULL,
    finalized_at TIMESTAMPTZ,
    reopened_at TIMESTAMPTZ,
    settled_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT uq_expense_ledgers_id_owner UNIQUE (id, owner_id),
    CONSTRAINT chk_expense_ledgers_state CHECK (state IN ('OPEN', 'FINALIZED', 'SETTLED')),
    CONSTRAINT chk_expense_ledgers_delete_state CHECK (deleted_at IS NULL OR state <> 'SETTLED')
);

CREATE INDEX idx_expense_ledgers_owner_state_updated
    ON expense_ledgers(owner_id, state, updated_at DESC, id DESC)
    WHERE deleted_at IS NULL;

CREATE TABLE expense_ledger_items (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES user_accounts(id),
    ledger_id UUID NOT NULL,
    name VARCHAR(160),
    merchant VARCHAR(160),
    amount_minor BIGINT NOT NULL,
    expense_date DATE NOT NULL,
    notes VARCHAR(2000),
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT fk_expense_ledger_items_owner FOREIGN KEY (ledger_id, owner_id)
        REFERENCES expense_ledgers(id, owner_id),
    CONSTRAINT uq_expense_ledger_items_id_owner UNIQUE (id, owner_id),
    CONSTRAINT chk_expense_ledger_items_amount CHECK (amount_minor > 0),
    CONSTRAINT chk_expense_ledger_items_name_or_merchant CHECK (name IS NOT NULL OR merchant IS NOT NULL)
);

CREATE INDEX idx_expense_ledger_items_ledger_date
    ON expense_ledger_items(ledger_id, expense_date DESC, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;

CREATE TABLE expense_ledger_settlements (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES user_accounts(id),
    ledger_id UUID NOT NULL,
    settlement_type VARCHAR(20) NOT NULL,
    settlement_amount_minor BIGINT NOT NULL,
    target_id UUID NOT NULL,
    settled_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_expense_ledger_settlements_owner FOREIGN KEY (ledger_id, owner_id)
        REFERENCES expense_ledgers(id, owner_id),
    CONSTRAINT uq_expense_ledger_settlements_id_owner UNIQUE (id, owner_id),
    CONSTRAINT uq_expense_ledger_settlements_ledger UNIQUE (ledger_id),
    CONSTRAINT chk_expense_ledger_settlements_type CHECK (settlement_type IN ('BILL', 'PAYBACK')),
    CONSTRAINT chk_expense_ledger_settlements_amount CHECK (settlement_amount_minor > 0)
);

CREATE INDEX idx_expense_ledger_settlements_owner_target
    ON expense_ledger_settlements(owner_id, settlement_type, target_id);

ALTER TABLE paycheck_entries
    ADD COLUMN source_expense_ledger_id UUID;

ALTER TABLE paycheck_entries
    ADD CONSTRAINT fk_paycheck_entries_source_expense_ledger_owner
    FOREIGN KEY (source_expense_ledger_id, owner_id)
    REFERENCES expense_ledgers(id, owner_id);

CREATE INDEX idx_paycheck_entries_source_expense_ledger
    ON paycheck_entries(source_expense_ledger_id, owner_id)
    WHERE source_expense_ledger_id IS NOT NULL;

ALTER TABLE paybacks
    ADD COLUMN source_expense_ledger_id UUID;

ALTER TABLE paybacks
    ADD CONSTRAINT fk_paybacks_source_expense_ledger_owner
    FOREIGN KEY (source_expense_ledger_id, owner_id)
    REFERENCES expense_ledgers(id, owner_id);

CREATE INDEX idx_paybacks_source_expense_ledger
    ON paybacks(source_expense_ledger_id, owner_id)
    WHERE source_expense_ledger_id IS NOT NULL;
