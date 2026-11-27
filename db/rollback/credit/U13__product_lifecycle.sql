-- Reverse V13__product_lifecycle.sql (rehearsal / isolated DB only).
alter table products
    alter constraint products_code_current_version_fkey not deferrable;

alter table product_versions
    drop constraint if exists product_versions_effective_window,
    drop column if exists agent_commission_rate_bps,
    drop column if exists effective_to,
    drop column if exists effective_from,
    drop column if exists lifecycle_status;
