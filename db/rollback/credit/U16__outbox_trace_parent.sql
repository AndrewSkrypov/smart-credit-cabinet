-- Reverse V16__outbox_trace_parent.sql (rehearsal / isolated DB only).
alter table delivery_outbox
    drop column if exists trace_parent;
