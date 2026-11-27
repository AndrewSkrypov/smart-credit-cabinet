create table products (
    code varchar(32) primary key,
    name varchar(255) not null,
    description text not null,
    purposes varchar(32)[] not null,
    min_amount bigint not null,
    max_amount bigint not null,
    min_term_months integer not null,
    max_term_months integer not null,
    base_rate numeric(5, 2) not null,
    auto_approval_limit bigint not null,
    revolving boolean not null,
    display_order integer not null,
    current_version bigint not null default 1,
    check (min_amount > 0 and min_amount <= max_amount),
    check (min_term_months > 0 and min_term_months <= max_term_months)
);

create table product_versions (
    code varchar(32) not null references products(code),
    version bigint not null check (version > 0),
    name varchar(255) not null,
    description text not null,
    purposes varchar(32)[] not null check (cardinality(purposes) > 0),
    min_amount bigint not null,
    max_amount bigint not null,
    min_term_months integer not null,
    max_term_months integer not null,
    base_rate numeric(5, 2) not null check (base_rate > 0 and base_rate <= 100),
    auto_approval_limit bigint not null,
    revolving boolean not null,
    active boolean not null,
    author_id uuid,
    reason varchar(1000) not null,
    created_at timestamptz not null,
    author_name varchar(255),
    author_role varchar(40),
    lifecycle_status varchar(16) not null default 'ACTIVE'
        check (lifecycle_status in ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    effective_from timestamptz,
    effective_to timestamptz,
    agent_commission_rate_bps integer not null default 150 check (agent_commission_rate_bps between 0 and 1000),
    primary key (code, version),
    check (min_amount > 0 and min_amount <= max_amount),
    check (min_term_months > 0 and min_term_months <= max_term_months and max_term_months <= 120),
    check (auto_approval_limit between min_amount and max_amount),
    check (effective_from is null or effective_to is null or effective_from <= effective_to)
);

alter table products add foreign key (code, current_version)
    references product_versions(code, version) deferrable initially deferred;

create function product_versions_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'product_versions is append-only';
end
$$;

create trigger product_versions_no_update before update or delete on product_versions
    for each row execute function product_versions_append_only();

create schema catalog_cutover;
create table catalog_cutover.authority (
    singleton boolean primary key default true check (singleton),
    writable boolean not null
);
insert into catalog_cutover.authority (writable) values (false);

create function catalog_cutover.lock_authority() returns boolean
    language sql security definer set search_path = pg_catalog as $$
    select writable from catalog_cutover.authority where singleton for share
$$;
revoke all on function catalog_cutover.lock_authority() from public;

create function catalog_cutover.require_authority() returns trigger
    language plpgsql set search_path = pg_catalog as $$
begin
    if catalog_cutover.lock_authority() is distinct from true then
        raise exception using errcode = '55000', message = 'Catalog writer is fenced';
    end if;
    return null;
end
$$;

create trigger catalog_write_fence before insert or update or delete or truncate on products
    for each statement execute function catalog_cutover.require_authority();
create trigger catalog_write_fence before insert or update or delete or truncate on product_versions
    for each statement execute function catalog_cutover.require_authority();
