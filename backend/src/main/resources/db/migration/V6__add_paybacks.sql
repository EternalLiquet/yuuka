CREATE TABLE paybacks (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES user_accounts(id),
    name VARCHAR(160) NOT NULL,
    original_amount_minor BIGINT NOT NULL,
    opening_remaining_amount_minor BIGINT NOT NULL,
    borrowed_date DATE NOT NULL,
    source VARCHAR(160),
    notes VARCHAR(2000),
    state VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    version BIGINT NOT NULL,
    CONSTRAINT chk_paybacks_original_positive CHECK (original_amount_minor > 0),
    CONSTRAINT chk_paybacks_opening_nonnegative CHECK (opening_remaining_amount_minor >= 0),
    CONSTRAINT chk_paybacks_opening_lte_original CHECK (opening_remaining_amount_minor <= original_amount_minor),
    CONSTRAINT chk_paybacks_state CHECK (state IN ('ACTIVE', 'PAID_OFF'))
);

CREATE INDEX idx_paybacks_owner_state_updated
    ON paybacks(owner_id, state, updated_at DESC)
    WHERE deleted_at IS NULL;

ALTER TABLE paycheck_entries
    ADD COLUMN payback_id UUID REFERENCES paybacks(id);

CREATE INDEX idx_paycheck_entries_payback_owner
    ON paycheck_entries(payback_id, owner_id)
    WHERE payback_id IS NOT NULL AND deleted_at IS NULL;

CREATE TABLE payback_repayments (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES user_accounts(id),
    payback_id UUID NOT NULL REFERENCES paybacks(id),
    entry_id UUID NOT NULL REFERENCES paycheck_entries(id),
    amount_minor BIGINT NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL,
    reversed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT chk_payback_repayments_amount_positive CHECK (amount_minor > 0)
);

CREATE UNIQUE INDEX ux_payback_repayments_active_entry
    ON payback_repayments(entry_id)
    WHERE reversed_at IS NULL;

CREATE INDEX idx_payback_repayments_payback_owner
    ON payback_repayments(payback_id, owner_id, applied_at DESC);
