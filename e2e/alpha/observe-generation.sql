-- One Analytics database is bound to exactly one topic and bootstrap snapshot.
select coalesce((select to_jsonb(g) from application_status_generation g where singleton), 'null');
