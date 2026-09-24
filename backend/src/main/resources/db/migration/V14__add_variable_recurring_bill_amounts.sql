alter table recurring_bill_definitions
    add column amount_mode varchar(20) not null default 'FIXED';

alter table recurring_bill_definitions
    alter column typical_amount_minor drop not null,
    drop constraint chk_recurring_bill_typical_amount,
    add constraint chk_recurring_bill_amount_mode check (amount_mode in ('FIXED', 'VARIABLE')),
    add constraint chk_recurring_bill_typical_amount check (
        (amount_mode = 'FIXED' and typical_amount_minor is not null and typical_amount_minor >= 0)
        or (amount_mode = 'VARIABLE' and (typical_amount_minor is null or typical_amount_minor >= 0))
    );

create table recurring_bill_occurrence_amounts (
    id uuid primary key,
    owner_id uuid not null,
    definition_id uuid not null,
    occurrence_date date not null,
    amount_minor bigint not null,
    version bigint not null default 0,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint fk_recurring_bill_occurrence_amount_owner
        foreign key (definition_id, owner_id)
        references recurring_bill_definitions (id, owner_id),
    constraint uq_recurring_bill_occurrence_amount unique (definition_id, occurrence_date),
    constraint chk_recurring_bill_occurrence_amount check (amount_minor >= 0)
);

create index idx_recurring_bill_occurrence_amount_owner_range
    on recurring_bill_occurrence_amounts (owner_id, occurrence_date, definition_id);
