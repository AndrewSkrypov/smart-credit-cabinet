\set ON_ERROR_STOP on
begin;
do $$
begin
    if not exists (select 1 from catalog_cutover.authority where singleton and not writable) then
        raise exception 'An inactive catalog writer is open';
    end if;
    begin
        -- Statement-level triggers fire even when the UPDATE matches no rows.
        update public.products set current_version = current_version where false;
        raise exception 'The inactive runtime can bypass its fence';
    exception when sqlstate '55000' then
        null;
    end;
end
$$;
rollback;
