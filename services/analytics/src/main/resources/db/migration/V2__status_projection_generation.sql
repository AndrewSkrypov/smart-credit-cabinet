CREATE TABLE application_status_generation (
    singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK (singleton),
    generation TEXT NOT NULL CHECK (generation ~ '^[a-zA-Z0-9._-]{1,249}$' AND generation NOT IN ('.', '..')),
    initialized_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    snapshot_sha256 TEXT NOT NULL CHECK (snapshot_sha256 ~ '^[a-f0-9]{64}$')
);

COMMENT ON TABLE application_status_generation IS 'Projection database binding to exactly one Kafka topic and bootstrap snapshot.';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'analytics_app') THEN
        REVOKE INSERT, UPDATE, DELETE ON application_status_generation FROM analytics_app;
    END IF;
END
$$;
