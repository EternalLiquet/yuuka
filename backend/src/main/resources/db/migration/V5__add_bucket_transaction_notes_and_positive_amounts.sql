alter table bucket_transactions
    add column notes varchar(1000);

alter table bucket_transactions
    add constraint chk_bucket_transaction_positive_amount check (amount_minor > 0) not valid;