alter table paycheck_entries
    add column payment_method varchar(20);

alter table template_entries
    add column payment_method varchar(20);

update paycheck_entries
set payment_method = 'AUTOPAY'
where entry_type = 'BILL';

update template_entries
set payment_method = 'AUTOPAY'
where entry_type = 'BILL';

alter table paycheck_entries
    add constraint chk_paycheck_entry_payment_method_value check (
        payment_method is null or payment_method in ('AUTOPAY', 'MANUAL')
    ),
    add constraint chk_paycheck_entry_payment_method_type check (
        (entry_type = 'BILL' and payment_method is not null)
        or (entry_type <> 'BILL' and payment_method is null)
    );

alter table template_entries
    add constraint chk_template_entry_payment_method_value check (
        payment_method is null or payment_method in ('AUTOPAY', 'MANUAL')
    ),
    add constraint chk_template_entry_payment_method_type check (
        (entry_type = 'BILL' and payment_method is not null)
        or (entry_type <> 'BILL' and payment_method is null)
    );
