alter table documents add column storage_backend varchar(16) not null default 'LOCAL'
    check (storage_backend in ('LOCAL', 'S3'));
alter table documents add column checksum_sha256 varchar(64)
    check (checksum_sha256 ~ '^[0-9a-f]{64}$');

-- Legacy checksums are filled only after reading the source and verifying the copied bytes.
-- A migration may change the backend, but cannot rewrite an already recorded content hash.
create function document_checksum_immutable() returns trigger language plpgsql as $$
begin
    if old.checksum_sha256 is not null and new.checksum_sha256 is distinct from old.checksum_sha256 then
        raise exception 'document checksum is immutable';
    end if;
    return new;
end
$$;
create trigger documents_checksum_immutable before update on documents
    for each row execute function document_checksum_immutable();
