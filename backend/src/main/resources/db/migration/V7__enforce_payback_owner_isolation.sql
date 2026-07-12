ALTER TABLE paybacks
    ADD CONSTRAINT uq_paybacks_id_owner UNIQUE (id, owner_id);

ALTER TABLE paycheck_entries
    ADD CONSTRAINT fk_paycheck_entries_payback_owner
        FOREIGN KEY (payback_id, owner_id)
        REFERENCES paybacks (id, owner_id);

ALTER TABLE payback_repayments
    ADD CONSTRAINT fk_payback_repayments_payback_owner
        FOREIGN KEY (payback_id, owner_id)
        REFERENCES paybacks (id, owner_id),
    ADD CONSTRAINT fk_payback_repayments_entry_owner
        FOREIGN KEY (entry_id, owner_id)
        REFERENCES paycheck_entries (id, owner_id);
