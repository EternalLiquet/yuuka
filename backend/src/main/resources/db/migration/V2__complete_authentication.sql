alter table user_accounts
    add column currency_code varchar(3) not null default 'USD',
    add column timezone varchar(64) not null default 'America/Indianapolis',
    add column enabled boolean not null default true;

create table refresh_tokens (
    id uuid primary key,
    user_id uuid not null references user_accounts (id) on delete cascade,
    family_id uuid not null,
    token_hash varchar(64) not null unique,
    expires_at timestamp with time zone not null,
    revoked_at timestamp with time zone,
    replaced_by_token_id uuid references refresh_tokens (id),
    created_at timestamp with time zone not null,
    constraint chk_refresh_token_expiry check (expires_at > created_at)
);

create index idx_refresh_tokens_user on refresh_tokens (user_id);
create index idx_refresh_tokens_family on refresh_tokens (family_id);
create index idx_refresh_tokens_expires on refresh_tokens (expires_at);
