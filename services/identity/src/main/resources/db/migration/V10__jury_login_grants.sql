alter table users add column jury_link_enabled boolean not null default false;

create table jury_login_grants (
    token_hash char(64) primary key check (token_hash ~ '^[0-9a-f]{64}$'),
    user_id uuid not null references users(id) on delete cascade,
    credentials_version integer not null,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    used_at timestamptz,
    revoked_at timestamptz,
    check (expires_at > created_at)
);

create index idx_jury_login_grants_user on jury_login_grants(user_id);
