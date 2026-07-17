alter table user_accounts
    add column recurring_bill_suggestion_days integer not null default 7,
    add constraint chk_user_recurring_bill_suggestion_days
        check (recurring_bill_suggestion_days between 1 and 31);

create table recurring_bill_definitions (
    id uuid primary key,
    owner_id uuid not null references user_accounts (id),
    name varchar(160) not null,
    typical_amount_minor bigint not null,
    payment_method varchar(20) not null,
    recurrence_type varchar(20) not null default 'MONTHLY',
    due_day integer not null,
    account_name varchar(160),
    payee varchar(160),
    notes varchar(2000),
    active boolean not null default true,
    version bigint not null default 0,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    deleted_at timestamp with time zone,
    constraint uq_recurring_bill_definitions_id_owner unique (id, owner_id),
    constraint chk_recurring_bill_typical_amount check (typical_amount_minor >= 0),
    constraint chk_recurring_bill_payment_method check (payment_method in ('AUTOPAY', 'MANUAL')),
    constraint chk_recurring_bill_recurrence_type check (recurrence_type = 'MONTHLY'),
    constraint chk_recurring_bill_due_day check (due_day between 1 and 31)
);

create index idx_recurring_bill_owner_deleted
    on recurring_bill_definitions (owner_id, deleted_at);
create index idx_recurring_bill_owner_active_due_day
    on recurring_bill_definitions (owner_id, active, due_day);

alter table paycheck_entries
    add column source_recurring_bill_definition_id uuid,
    add column source_recurring_occurrence_date date,
    add constraint fk_paycheck_entry_recurring_bill_owner
        foreign key (source_recurring_bill_definition_id, owner_id)
        references recurring_bill_definitions (id, owner_id),
    add constraint chk_paycheck_entry_recurring_source_pair check (
        (source_recurring_bill_definition_id is null and source_recurring_occurrence_date is null)
        or (source_recurring_bill_definition_id is not null and source_recurring_occurrence_date is not null)
    ),
    add constraint chk_paycheck_entry_recurring_source_type check (
        source_recurring_bill_definition_id is null or entry_type = 'BILL'
    );

create index idx_paycheck_entry_recurring_occurrence
    on paycheck_entries (owner_id, source_recurring_bill_definition_id, source_recurring_occurrence_date)
    where source_recurring_bill_definition_id is not null and deleted_at is null;
