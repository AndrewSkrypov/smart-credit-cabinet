CREATE TABLE application_status_versions (
    application_id UUID NOT NULL,
    application_version BIGINT NOT NULL CHECK (application_version >= 0),
    status TEXT NOT NULL,
    PRIMARY KEY (application_id, application_version)
);

CREATE TABLE application_status_projection (
    application_id UUID PRIMARY KEY,
    application_version BIGINT NOT NULL CHECK (application_version >= 0),
    status TEXT NOT NULL
);

CREATE TABLE application_status_receipts (
    event_id UUID PRIMARY KEY,
    application_id UUID NOT NULL,
    application_version BIGINT NOT NULL CHECK (application_version >= 0),
    status TEXT NOT NULL,
    occurred_at TEXT NOT NULL
);

COMMENT ON TABLE application_status_versions IS 'First observed status at each version, including snapshot rows, for conflict detection.';
COMMENT ON TABLE application_status_projection IS 'Highest observed application version and its current status.';
COMMENT ON TABLE application_status_receipts IS 'Status journal event IDs and canonical v1 values committed with the projection.';
COMMENT ON COLUMN application_status_receipts.occurred_at IS 'Canonical UTC instant with original precision for receipt comparison.';
