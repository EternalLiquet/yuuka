create index idx_paycheck_entries_owner_amount_live
    on paycheck_entries (owner_id, amount_minor, paycheck_id, position, id)
    where deleted_at is null;

create index idx_paycheck_entries_owner_name_live
    on paycheck_entries (owner_id, lower(name), paycheck_id, position, id)
    where deleted_at is null;

create index idx_paychecks_owner_name_income
    on paychecks (owner_id, lower(name), income_date desc, id);
