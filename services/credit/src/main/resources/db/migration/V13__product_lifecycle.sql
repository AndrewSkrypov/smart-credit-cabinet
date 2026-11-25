alter table product_versions
    add column lifecycle_status varchar(16) not null default 'ACTIVE'
        check (lifecycle_status in ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    add column effective_from timestamptz,
    add column effective_to timestamptz,
    add column agent_commission_rate_bps integer not null default 150
        check (agent_commission_rate_bps between 0 and 1000),
    add constraint product_versions_effective_window
        check (effective_from is null or effective_to is null or effective_from <= effective_to);

-- Backfill existing historical versions without removing the append-only trigger.
alter table product_versions disable trigger product_versions_no_update;
update product_versions
set lifecycle_status = case when active then 'ACTIVE' else 'ARCHIVED' end;
alter table product_versions enable trigger product_versions_no_update;

-- Allow inserting a new product and its initial v1 row in product_versions within one transaction.
alter table products
    alter constraint products_code_current_version_fkey deferrable initially deferred;
