BEGIN;

CREATE TEMP TABLE requested_status_generation (
    generation text NOT NULL,
    snapshot_sha256 text NOT NULL
) ON COMMIT DROP;
INSERT INTO requested_status_generation VALUES (:'generation', :'snapshot_sha256');

LOCK TABLE application_status_generation IN SHARE ROW EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM application_status_generation actual
        CROSS JOIN requested_status_generation requested
        WHERE (actual.generation, actual.snapshot_sha256) <> (requested.generation, requested.snapshot_sha256)
    ) THEN
        RAISE EXCEPTION 'Analytics database already belongs to a different generation or snapshot';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM application_status_generation)
        AND (
            EXISTS (SELECT 1 FROM application_status_projection)
            OR EXISTS (SELECT 1 FROM application_status_versions)
            OR EXISTS (SELECT 1 FROM application_status_receipts)
        )
    THEN
        RAISE EXCEPTION 'Use an empty Analytics database for the first bootstrap';
    END IF;
END
$$;

CREATE TEMP TABLE status_snapshot (
    application_id uuid PRIMARY KEY,
    application_version bigint NOT NULL CHECK (application_version >= 0),
    status text NOT NULL CHECK (status IN (
        'DRAFT', 'PENDING_CLIENT_CONFIRMATION', 'SUBMITTED', 'INFO_REQUESTED',
        'ENRICHMENT', 'SCORING', 'MANUAL_REVIEW', 'OFFER_ISSUED', 'SIGNING',
        'DISBURSING', 'DISBURSED', 'REJECTED', 'WITHDRAWN', 'CLOSED'
    ))
) ON COMMIT DROP;

\copy status_snapshot (application_id, application_version, status) FROM PSTDIN WITH (FORMAT csv, HEADER MATCH)

INSERT INTO application_status_versions (application_id, application_version, status)
SELECT application_id, application_version, status FROM status_snapshot
ON CONFLICT (application_id, application_version) DO NOTHING;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM status_snapshot snapshot
        JOIN application_status_versions observed USING (application_id, application_version)
        WHERE snapshot.status <> observed.status
    ) THEN
        RAISE EXCEPTION 'Analytics snapshot conflicts with an observed status version';
    END IF;
END
$$;

INSERT INTO application_status_projection (application_id, application_version, status)
SELECT application_id, application_version, status FROM status_snapshot
ON CONFLICT (application_id) DO UPDATE
SET application_version = EXCLUDED.application_version, status = EXCLUDED.status
WHERE application_status_projection.application_version < EXCLUDED.application_version;

INSERT INTO application_status_generation (generation, snapshot_sha256)
SELECT generation, snapshot_sha256 FROM requested_status_generation
ON CONFLICT (singleton) DO NOTHING;

COMMIT;
