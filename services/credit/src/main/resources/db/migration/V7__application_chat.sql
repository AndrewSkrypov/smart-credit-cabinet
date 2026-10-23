alter table documents add column context varchar(16) not null default 'BUSINESS'
    check (context in ('BUSINESS', 'CHAT'));
alter table documents add unique (id, application_id);

create table application_chat_state (
    application_id uuid primary key references applications(id),
    last_seq bigint not null default 0 check (last_seq >= 0)
);

create table application_messages (
    id uuid primary key,
    application_id uuid not null references application_chat_state(application_id),
    seq bigint not null check (seq > 0),
    author_id uuid not null,
    author_name varchar(255) not null,
    author_role varchar(32) not null,
    side varchar(16) not null check (side in ('COMPANY', 'BANK')),
    message_text varchar(2000) not null,
    idempotency_key uuid not null,
    payload_hash char(64) not null,
    created_at timestamptz not null,
    unique (application_id, seq),
    unique (application_id, author_id, idempotency_key),
    unique (id, application_id)
);
create index application_messages_unread on application_messages(application_id, side, seq);

create table message_attachments (
    message_id uuid not null,
    application_id uuid not null,
    document_id uuid not null,
    primary key (message_id, document_id),
    foreign key (message_id, application_id) references application_messages(id, application_id),
    foreign key (document_id, application_id) references documents(id, application_id)
);
create index message_attachments_document on message_attachments(document_id);

create table application_chat_read_cursors (
    application_id uuid not null references application_chat_state(application_id),
    side varchar(16) not null check (side in ('COMPANY', 'BANK')),
    last_read_seq bigint not null check (last_read_seq >= 0),
    actor_id uuid not null,
    updated_at timestamptz not null,
    primary key (application_id, side)
);

create table application_chat_upload_keys (
    application_id uuid not null references application_chat_state(application_id),
    actor_id uuid not null,
    idempotency_key uuid not null,
    payload_hash char(64) not null,
    document_id uuid not null,
    created_at timestamptz not null,
    primary key (application_id, actor_id, idempotency_key),
    foreign key (document_id, application_id) references documents(id, application_id)
);

create table application_chat_audit (
    id uuid primary key,
    application_id uuid not null references applications(id),
    actor_id uuid not null,
    actor_role varchar(32) not null,
    action varchar(32) not null check (action in ('MESSAGE_SENT', 'ATTACHMENT_UPLOADED', 'READ_CURSOR_ADVANCED')),
    message_id uuid,
    document_id uuid,
    read_seq bigint,
    occurred_at timestamptz not null,
    foreign key (message_id, application_id) references application_messages(id, application_id),
    foreign key (document_id, application_id) references documents(id, application_id)
);
create index application_chat_audit_history on application_chat_audit(application_id, occurred_at desc, id desc);

create table application_chat_rate_buckets (
    actor_id uuid primary key,
    window_start timestamptz not null,
    hits integer not null check (hits between 1 and 30)
);

create function application_chat_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'chat history is append-only';
end
$$;
create trigger application_messages_no_update before update or delete on application_messages
    for each row execute function application_chat_append_only();
create trigger message_attachments_no_update before update or delete on message_attachments
    for each row execute function application_chat_append_only();
create trigger application_chat_audit_no_update before update or delete on application_chat_audit
    for each row execute function application_chat_append_only();
create trigger application_chat_upload_keys_no_update before update or delete on application_chat_upload_keys
    for each row execute function application_chat_append_only();

create function preserve_attached_document() returns trigger language plpgsql as $$
begin
    if exists (select 1 from message_attachments where document_id = old.id) and
       (new.application_id, new.stored_name, new.file_name, new.size_bytes, new.type, new.context, new.uploaded_by, new.uploaded_at, new.deleted_at) is distinct from
       (old.application_id, old.stored_name, old.file_name, old.size_bytes, old.type, old.context, old.uploaded_by, old.uploaded_at, old.deleted_at) then
        raise exception 'attached document is immutable';
    end if;
    return new;
end
$$;
create trigger documents_preserve_chat_attachment before update on documents
    for each row execute function preserve_attached_document();

create function preserve_chat_cursor() returns trigger language plpgsql as $$
begin
    if new.last_read_seq > (select last_seq from application_chat_state where application_id = new.application_id) then
        raise exception 'chat cursor exceeds the visible sequence';
    end if;
    if tg_op = 'UPDATE' and new.last_read_seq < old.last_read_seq then
        raise exception 'chat cursor cannot move backwards';
    end if;
    return new;
end
$$;
create trigger application_chat_cursor_guard before insert or update on application_chat_read_cursors
    for each row execute function preserve_chat_cursor();
