ALTER TABLE recurring_bill_definitions
    ADD COLUMN planning_amount_minor bigint,
    ADD CONSTRAINT recurring_bill_planning_amount_nonnegative CHECK (planning_amount_minor >= 0);

ALTER TABLE paycheck_entries
    ADD COLUMN amount_estimated boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT estimated_amount_requires_recurring_bill CHECK (
        NOT amount_estimated OR (entry_type = 'BILL' AND source_recurring_bill_definition_id IS NOT NULL
            AND source_recurring_occurrence_date IS NOT NULL)
    );
