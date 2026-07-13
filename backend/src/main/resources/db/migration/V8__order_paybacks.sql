ALTER TABLE paybacks
    ADD COLUMN position INTEGER;

WITH ordered AS (
    SELECT
        id,
        ROW_NUMBER() OVER (PARTITION BY owner_id ORDER BY created_at, id) - 1 AS next_position
    FROM paybacks
)
UPDATE paybacks
SET position = ordered.next_position
FROM ordered
WHERE paybacks.id = ordered.id;

ALTER TABLE paybacks
    ALTER COLUMN position SET NOT NULL,
    ADD CONSTRAINT chk_paybacks_position_nonnegative CHECK (position >= 0);

CREATE UNIQUE INDEX ux_paybacks_owner_live_position
    ON paybacks(owner_id, position)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_paybacks_owner_position
    ON paybacks(owner_id, position, created_at, id)
    WHERE deleted_at IS NULL;
