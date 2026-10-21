-- Identity owns the people who use the cabinet and the companies clients act for. The
-- registry profile of a company (ОГРН, ОКВЭД, director) lives in the credit service.
create table companies (
    id uuid primary key,
    inn varchar(12) not null unique,
    name varchar(500),
    created_at timestamptz not null default now()
);

create table users (
    id uuid primary key,
    full_name varchar(255) not null,
    email varchar(255) not null unique,
    role varchar(32) not null,
    status varchar(32) not null default 'ACTIVE',
    company_id uuid references companies(id),
    can_sign boolean not null default false,
    password_hash varchar(255) not null,
    credentials_version integer not null default 0,
    created_at timestamptz not null default now(),
    last_sign_in_at timestamptz,
    -- Bank staff never act for a company; signing rights exist only inside one.
    constraint users_company_only_for_clients check (role = 'CLIENT' or company_id is null),
    constraint users_sign_needs_company check (not can_sign or company_id is not null)
);

create index idx_users_company on users(company_id);

-- Consents to the processing of personal data (152-FZ, art. 9): who accepted which edition of
-- the document and when. Sign-up records one; the rows are evidence, so they are never edited.
create table personal_data_consents (
    id uuid primary key,
    -- Checked at commit: sign-up writes the consent in the same transaction as the user, and
    -- JPA inserts the user only when it flushes.
    user_id uuid not null references users(id) on delete cascade deferrable initially deferred,
    document varchar(32) not null,
    version varchar(32) not null,
    accepted_at timestamptz not null default now()
);

create index idx_personal_data_consents_user on personal_data_consents(user_id, document, accepted_at desc);

create function personal_data_consents_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'personal_data_consents is append-only';
end
$$;

create trigger personal_data_consents_no_update
    before update on personal_data_consents
    for each row execute function personal_data_consents_append_only();
