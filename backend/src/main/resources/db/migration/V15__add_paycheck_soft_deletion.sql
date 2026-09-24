alter table paychecks
    add column deleted_at timestamp with time zone;

create index idx_paychecks_owner_live
    on paychecks (owner_id, state, income_date desc)
    where deleted_at is null;
