alter table agent_clients
    add column expires_at timestamptz,
    add column issued_source varchar(16) not null default 'LEGACY',
    add column issued_by uuid,
    add column revoked_source varchar(16),
    add column revoked_by uuid;

update agent_clients set revoked_source = 'LEGACY' where status = 'REVOKED';

alter table agent_clients
    drop constraint agent_clients_status_check,
    add constraint agent_clients_status_check check (status in ('ACTIVE', 'EXPIRED', 'REVOKED')),
    add constraint agent_clients_expiry_check check (expires_at is null or expires_at > assigned_at),
    add constraint agent_clients_issue_provenance_check
        check ((issued_source = 'LEGACY' and issued_by is null) or (issued_source = 'COMPANY' and issued_by is not null)),
    add constraint agent_clients_revoke_provenance_check
        check (revoked_source is null and revoked_by is null and status <> 'REVOKED'
            or revoked_source is not null and status = 'REVOKED'
                and ((revoked_source = 'LEGACY' and revoked_by is null) or (revoked_source = 'COMPANY' and revoked_by is not null)));

create index idx_agent_clients_company on agent_clients(company_id, assigned_at desc, id desc);
