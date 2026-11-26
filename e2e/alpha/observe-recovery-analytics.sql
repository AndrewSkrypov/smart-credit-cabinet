-- Evidence of event application even when a replay would leave the status unchanged.
select coalesce(jsonb_agg(jsonb_build_object(
    'eventId', event_id, 'applicationId', application_id, 'applicationVersion', application_version, 'status', status
) order by event_id), '[]')
from application_status_receipts
where application_id in (
    '10000000-0000-4000-8000-000000000005',
    '10000000-0000-4000-8000-000000000010',
    '10000000-0000-4000-8000-000000000099'
);
