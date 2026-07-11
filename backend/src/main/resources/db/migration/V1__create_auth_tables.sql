create table user_accounts (
    id uuid primary key,
    email varchar(320) not null unique,
    password_hash varchar(255) not null,
    display_name varchar(120),
    role varchar(40) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index idx_user_accounts_email on user_accounts (email);
