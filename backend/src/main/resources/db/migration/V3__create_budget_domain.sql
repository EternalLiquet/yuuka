create table templates (
    id uuid primary key,
    owner_id uuid not null references user_accounts (id),
    name varchar(120) not null,
    description varchar(1000),
    archived boolean not null default false,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    archived_at timestamp with time zone,
    version bigint not null default 0,
    constraint uq_templates_id_owner unique (id, owner_id)
);

create index idx_templates_owner_archived on templates (owner_id, archived, updated_at desc);

create table template_entries (
    id uuid primary key,
    owner_id uuid not null references user_accounts (id),
    template_id uuid not null,
    entry_type varchar(32) not null,
    name varchar(160) not null,
    default_amount_minor bigint not null,
    position integer not null,
    default_due_offset_days integer,
    account_name varchar(160),
    payee varchar(160),
    notes varchar(2000),
    target_minor bigint,
    target_date date,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    version bigint not null default 0,
    constraint fk_template_entries_owner foreign key (template_id, owner_id)
        references templates (id, owner_id) on delete cascade,
    constraint uq_template_entries_id_owner unique (id, owner_id),
    constraint chk_template_entry_type check (entry_type in ('BILL', 'SPENDING_BUCKET', 'SINKING_FUND')),
    constraint chk_template_entry_amount check (default_amount_minor >= 0),
    constraint chk_template_entry_target check (target_minor is null or target_minor >= 0),
    constraint chk_template_entry_position check (position >= 0),
    constraint uq_template_entry_position unique (template_id, position)
);

create index idx_template_entries_order on template_entries (template_id, position);

create table paychecks (
    id uuid primary key,
    owner_id uuid not null references user_accounts (id),
    name varchar(120) not null,
    source varchar(160),
    amount_minor bigint not null,
    income_date date not null,
    state varchar(20) not null,
    template_source_id uuid,
    notes varchar(2000),
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    closed_at timestamp with time zone,
    reopened_at timestamp with time zone,
    archived_at timestamp with time zone,
    version bigint not null default 0,
    constraint uq_paychecks_id_owner unique (id, owner_id),
    constraint fk_paycheck_template_owner foreign key (template_source_id, owner_id)
        references templates (id, owner_id),
    constraint chk_paycheck_amount check (amount_minor >= 0),
    constraint chk_paycheck_state check (state in ('ACTIVE', 'CLOSED', 'ARCHIVED'))
);

create index idx_paychecks_owner_state_date on paychecks (owner_id, state, income_date desc);
create index idx_paychecks_owner_updated on paychecks (owner_id, updated_at desc);

create table paycheck_entries (
    id uuid primary key,
    owner_id uuid not null references user_accounts (id),
    paycheck_id uuid not null,
    entry_type varchar(32) not null,
    name varchar(160) not null,
    amount_minor bigint not null,
    status varchar(20) not null,
    position integer not null,
    due_date date,
    account_name varchar(160),
    payee varchar(160),
    notes varchar(2000),
    target_minor bigint,
    target_date date,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    deleted_at timestamp with time zone,
    version bigint not null default 0,
    constraint fk_paycheck_entries_owner foreign key (paycheck_id, owner_id)
        references paychecks (id, owner_id),
    constraint uq_paycheck_entries_id_owner unique (id, owner_id),
    constraint chk_paycheck_entry_type check (entry_type in ('BILL', 'SPENDING_BUCKET', 'SINKING_FUND')),
    constraint chk_paycheck_entry_status check (status in ('NOT_PAID', 'PROCESSING', 'POSTED')),
    constraint chk_paycheck_entry_amount check (amount_minor >= 0),
    constraint chk_paycheck_entry_target check (target_minor is null or target_minor >= 0),
    constraint chk_paycheck_entry_position check (position >= 0)
);

create unique index uq_live_paycheck_entry_position
    on paycheck_entries (paycheck_id, position) where deleted_at is null;
create index idx_paycheck_entries_order on paycheck_entries (paycheck_id, position);
create index idx_paycheck_entries_owner on paycheck_entries (owner_id, updated_at desc);

create table entry_status_events (
    id uuid primary key,
    owner_id uuid not null references user_accounts (id),
    entry_id uuid not null,
    from_status varchar(20),
    to_status varchar(20) not null,
    effective_at timestamp with time zone not null,
    recorded_at timestamp with time zone not null,
    note varchar(1000),
    constraint fk_status_events_owner foreign key (entry_id, owner_id)
        references paycheck_entries (id, owner_id),
    constraint chk_status_event_from check (from_status is null or from_status in ('NOT_PAID', 'PROCESSING', 'POSTED')),
    constraint chk_status_event_to check (to_status in ('NOT_PAID', 'PROCESSING', 'POSTED'))
);

create index idx_status_events_entry_recorded on entry_status_events (entry_id, recorded_at, id);

create table bucket_transactions (
    id uuid primary key,
    owner_id uuid not null references user_accounts (id),
    entry_id uuid not null,
    amount_minor bigint not null,
    description varchar(500),
    effective_date date not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    deleted_at timestamp with time zone,
    version bigint not null default 0,
    constraint fk_bucket_transactions_owner foreign key (entry_id, owner_id)
        references paycheck_entries (id, owner_id)
);

create index idx_bucket_transactions_entry_date
    on bucket_transactions (entry_id, effective_date, created_at) where deleted_at is null;

create table audit_events (
    id uuid primary key,
    owner_id uuid not null references user_accounts (id),
    entity_type varchar(60) not null,
    entity_id uuid not null,
    action varchar(80) not null,
    effective_at timestamp with time zone,
    recorded_at timestamp with time zone not null,
    before_data jsonb,
    after_data jsonb,
    metadata jsonb
);

create index idx_audit_events_entity on audit_events (owner_id, entity_type, entity_id, recorded_at desc, id);

create or replace function reject_immutable_event_change()
returns trigger
language plpgsql
as $$
begin
    raise exception '% is append-only', tg_table_name;
end;
$$;

create trigger trg_entry_status_events_append_only
before update or delete on entry_status_events
for each row execute function reject_immutable_event_change();

create trigger trg_audit_events_append_only
before update or delete on audit_events
for each row execute function reject_immutable_event_change();
