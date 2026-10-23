-- Preserve every parameter used by the pipeline, including its alternative catalogue.
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
    base_rate numeric(5,2) not null check (base_rate > 0 and base_rate <= 100),
    auto_approval_limit bigint not null,
    revolving boolean not null,
    active boolean not null,
    author_id uuid,
    reason varchar(1000) not null,
    created_at timestamptz not null,
    primary key (code, version),
    check (min_amount > 0 and min_amount <= max_amount),
    check (min_term_months > 0 and min_term_months <= max_term_months and max_term_months <= 120),
    check (auto_approval_limit between min_amount and max_amount)
);
insert into product_versions
select code, 1, name, description, purposes, min_amount, max_amount, min_term_months,
       max_term_months, base_rate, auto_approval_limit, revolving, true, null,
       'Начальные условия каталога', now() from products;

alter table products add column current_version bigint not null default 1;
alter table products add foreign key (code, current_version) references product_versions(code, version);

alter table applications add column product_version bigint not null default 1;
alter table applications add column product_catalog_snapshot jsonb not null default '{}';
update applications set product_catalog_snapshot =
    (select jsonb_object_agg(code, current_version) from products) where status <> 'DRAFT';
alter table applications add foreign key (product_code, product_version) references product_versions(code, version);
alter table applications add check (status = 'DRAFT' or product_catalog_snapshot <> '{}'::jsonb);

alter table offers add column product_version bigint not null default 1;
alter table offers add foreign key (product_code, product_version) references product_versions(code, version);

create function product_versions_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'product_versions is append-only';
end
$$;
create trigger product_versions_no_update before update or delete on product_versions
    for each row execute function product_versions_append_only();
