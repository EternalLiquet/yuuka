CREATE TABLE sinking_funds (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES user_accounts(id),
    name VARCHAR(160) NOT NULL,
    target_minor BIGINT,
    target_date DATE,
    notes VARCHAR(2000),
    state VARCHAR(20) NOT NULL,
    position INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    archived_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_sinking_funds_id_owner UNIQUE (id, owner_id),
    CONSTRAINT chk_sinking_funds_target_nonnegative CHECK (target_minor IS NULL OR target_minor >= 0),
    CONSTRAINT chk_sinking_funds_state CHECK (state IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT chk_sinking_funds_position CHECK (position >= 0)
);

CREATE INDEX idx_sinking_funds_owner_state_position
    ON sinking_funds(owner_id, state, position, created_at, id);

ALTER TABLE paycheck_entries
    ADD COLUMN sinking_fund_id UUID;

ALTER TABLE paycheck_entries
    ADD CONSTRAINT fk_paycheck_entries_sinking_fund_owner
        FOREIGN KEY (sinking_fund_id, owner_id) REFERENCES sinking_funds(id, owner_id);

ALTER TABLE paycheck_entries
    ADD CONSTRAINT chk_paycheck_entries_sinking_fund_type
        CHECK (sinking_fund_id IS NULL OR entry_type = 'SINKING_FUND');

ALTER TABLE paycheck_entries
    ADD CONSTRAINT chk_paycheck_entries_single_balance_assignment
        CHECK (payback_id IS NULL OR sinking_fund_id IS NULL);

CREATE INDEX idx_paycheck_entries_sinking_fund_owner
    ON paycheck_entries(sinking_fund_id, owner_id)
    WHERE sinking_fund_id IS NOT NULL AND deleted_at IS NULL;

CREATE TABLE sinking_fund_transactions (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES user_accounts(id),
    sinking_fund_id UUID NOT NULL,
    entry_id UUID,
    transaction_type VARCHAR(32) NOT NULL,
    amount_minor BIGINT NOT NULL,
    effective_date DATE NOT NULL,
    reason VARCHAR(500),
    notes VARCHAR(2000),
    reversed_at TIMESTAMPTZ,
    reversal_reason VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_sinking_fund_transactions_fund_owner
        FOREIGN KEY (sinking_fund_id, owner_id) REFERENCES sinking_funds(id, owner_id),
    CONSTRAINT fk_sinking_fund_transactions_entry_owner
        FOREIGN KEY (entry_id, owner_id) REFERENCES paycheck_entries(id, owner_id),
    CONSTRAINT chk_sinking_fund_transactions_type
        CHECK (transaction_type IN ('OPENING_BALANCE', 'CONTRIBUTION', 'WITHDRAWAL')),
    CONSTRAINT chk_sinking_fund_transactions_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT chk_sinking_fund_transactions_entry_link CHECK (
        (transaction_type = 'CONTRIBUTION' AND entry_id IS NOT NULL)
        OR (transaction_type IN ('OPENING_BALANCE', 'WITHDRAWAL') AND entry_id IS NULL)
    ),
    CONSTRAINT chk_sinking_fund_withdrawal_reason CHECK (
        transaction_type <> 'WITHDRAWAL' OR reason IS NOT NULL
    )
);

CREATE UNIQUE INDEX ux_sinking_fund_active_contribution_entry
    ON sinking_fund_transactions(entry_id)
    WHERE transaction_type = 'CONTRIBUTION' AND reversed_at IS NULL;

CREATE INDEX idx_sinking_fund_transactions_fund_date
    ON sinking_fund_transactions(sinking_fund_id, owner_id, effective_date DESC, created_at DESC, id DESC);

CREATE INDEX idx_sinking_fund_transactions_entry_active
    ON sinking_fund_transactions(entry_id, owner_id)
    WHERE transaction_type = 'CONTRIBUTION' AND reversed_at IS NULL;
