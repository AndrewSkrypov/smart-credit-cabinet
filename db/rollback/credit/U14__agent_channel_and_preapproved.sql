-- Reverse V14__agent_channel_and_preapproved.sql (rehearsal / isolated DB only).
drop index if exists idx_applications_pre_approved;
drop index if exists idx_applications_agent;

alter table applications
    drop column if exists agent_commission_status,
    drop column if exists agent_commission_amount,
    drop column if exists agent_commission_rate_bps,
    drop column if exists pre_approved_offer_id,
    drop column if exists agent_name,
    drop column if exists agent_id;

drop index if exists idx_pre_approved_offers_company;
drop table if exists pre_approved_offers;
