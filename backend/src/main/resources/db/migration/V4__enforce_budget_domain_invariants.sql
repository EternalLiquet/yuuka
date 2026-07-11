alter table template_entries
    add constraint chk_template_entry_type_fields check (
        (entry_type = 'BILL'
            and target_minor is null
            and target_date is null)
        or (entry_type = 'SPENDING_BUCKET'
            and default_due_offset_days is null
            and account_name is null
            and payee is null
            and target_minor is null
            and target_date is null)
        or (entry_type = 'SINKING_FUND'
            and default_due_offset_days is null
            and account_name is null
            and payee is null)
    );

alter table paycheck_entries
    add constraint chk_paycheck_entry_type_fields check (
        (entry_type = 'BILL'
            and target_minor is null
            and target_date is null)
        or (entry_type = 'SPENDING_BUCKET'
            and due_date is null
            and account_name is null
            and payee is null
            and target_minor is null
            and target_date is null)
        or (entry_type = 'SINKING_FUND'
            and due_date is null
            and account_name is null
            and payee is null)
    );

alter table bucket_transactions
    add constraint chk_bucket_transaction_nonzero check (amount_minor <> 0);
