create table password_reset_requests (
    id uuid primary key,
    user_id uuid not null references users(id),
    token_hash char(64) not null unique,
    key_version varchar(32) not null,
    credentials_version integer not null,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    used_at timestamptz,
    revoked_at timestamptz,
    check (expires_at > created_at)
);
create index idx_password_reset_user on password_reset_requests(user_id) where used_at is null and revoked_at is null;

create table company_invitations (
    id uuid primary key,
    company_id uuid not null references companies(id),
    email varchar(255) not null,
    can_sign boolean not null,
    invited_by uuid not null references users(id),
    token_hash char(64) not null unique,
    key_version varchar(32) not null,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    used_at timestamptz,
    revoked_at timestamptz,
    accepted_by uuid references users(id),
    check (expires_at > created_at)
);
create unique index idx_company_invitation_open on company_invitations(company_id, email) where used_at is null and revoked_at is null;
create index idx_company_invitation_company on company_invitations(company_id, created_at);

create table identity_audit (
    id uuid primary key,
    actor_id uuid references users(id),
    action varchar(40) not null,
    subject_id uuid,
    company_id uuid references companies(id),
    occurred_at timestamptz not null,
    request_id varchar(64)
);
create index idx_identity_audit_company on identity_audit(company_id, occurred_at);
create function identity_audit_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'identity_audit is append-only';
end
$$;
create trigger identity_audit_no_change before update or delete on identity_audit
    for each row execute function identity_audit_append_only();

create table identity_mutation_keys (
    actor_id uuid not null references users(id),
    mutation_key uuid not null,
    operation varchar(40) not null,
    payload_hash char(64) not null,
    result_id uuid not null,
    created_at timestamptz not null default now(),
    primary key (actor_id, mutation_key)
);

-- Recovery limits survive service restarts; the shared limit port replaces this in the next storage release.
create table identity_rate_buckets (
    bucket_key char(64) primary key,
    window_start timestamptz not null,
    attempts integer not null check (attempts > 0)
);
