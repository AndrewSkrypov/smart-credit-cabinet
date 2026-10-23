-- A draft can be withdrawn before it ever captures the submission catalogue.
alter table applications drop constraint applications_check;
alter table applications add constraint applications_submitted_product_snapshot
    check (submitted_at is null or product_catalog_snapshot <> '{}'::jsonb);

create function preserve_submitted_product_snapshot() returns trigger language plpgsql as $$
begin
    if old.submitted_at is not null and
        (new.product_code, new.product_version, new.product_catalog_snapshot) is distinct from
        (old.product_code, old.product_version, old.product_catalog_snapshot) then
        raise exception 'submitted product snapshot is immutable';
    end if;
    return new;
end
$$;
create trigger applications_preserve_product_snapshot before update on applications
    for each row execute function preserve_submitted_product_snapshot();

create function preserve_offer_product_version() returns trigger language plpgsql as $$
begin
    if (new.product_code, new.product_version) is distinct from (old.product_code, old.product_version) then
        raise exception 'offer product version is immutable';
    end if;
    return new;
end
$$;
create trigger offers_preserve_product_version before update on offers
    for each row execute function preserve_offer_product_version();
