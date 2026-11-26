COPY (
    SELECT id AS application_id, version AS application_version, status
    FROM applications
    ORDER BY id
) TO STDOUT WITH (FORMAT CSV, HEADER TRUE);
