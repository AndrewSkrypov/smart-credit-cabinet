-- Agent sales channel: mandates linking partner agents to client companies and demo agent accounts.
create table agent_clients (
    id uuid primary key,
    agent_user_id uuid not null references users(id) on delete cascade,
    company_id uuid not null references companies(id) on delete cascade,
    status varchar(32) not null default 'ACTIVE' check (status in ('ACTIVE', 'REVOKED')),
    assigned_at timestamptz not null default now(),
    revoked_at timestamptz
);

create unique index idx_agent_clients_active
    on agent_clients(agent_user_id, company_id)
    where status = 'ACTIVE';

create index idx_agent_clients_agent
    on agent_clients(agent_user_id, status, assigned_at);

-- Demo agents share password "Demo2026" (same bcrypt hash as V2/V3) and respect DEMO_ACCOUNTS.
insert into users (id, full_name, email, role, status, company_id, can_sign, password_hash, created_at) values
('9b2f0c4a-6d1e-4f2a-8c3b-000000000201', 'Соколов Артём', 'agent@demo.test', 'AGENT',
    case when '${demo-accounts}' = 'true' then 'ACTIVE' else 'BLOCKED' end,
    null, false, '$2a$10$QAS4QGFj5n0t4bOUdzhGq.2R1di2Knm8WRphHGDFiVElZ/NKZBTCO', '2026-10-01T09:00:00Z'),
('9b2f0c4a-6d1e-4f2a-8c3b-000000000202', 'Морозова Елена', 'agent2@demo.test', 'AGENT',
    case when '${demo-accounts}' = 'true' then 'ACTIVE' else 'BLOCKED' end,
    null, false, '$2a$10$QAS4QGFj5n0t4bOUdzhGq.2R1di2Knm8WRphHGDFiVElZ/NKZBTCO', '2026-10-01T09:00:00Z');

insert into agent_clients (id, agent_user_id, company_id, status, assigned_at) values
('a1b2c3d4-0b8e-4c55-9a51-000000000001', '9b2f0c4a-6d1e-4f2a-8c3b-000000000201', '6f1d4c1e-0b8e-4c55-9a51-000000000001', 'ACTIVE', '2026-10-01T09:00:00Z'),
('a1b2c3d4-0b8e-4c55-9a51-000000000002', '9b2f0c4a-6d1e-4f2a-8c3b-000000000201', '6f1d4c1e-0b8e-4c55-9a51-000000000002', 'ACTIVE', '2026-10-01T09:05:00Z'),
('a1b2c3d4-0b8e-4c55-9a51-000000000003', '9b2f0c4a-6d1e-4f2a-8c3b-000000000201', '6f1d4c1e-0b8e-4c55-9a51-000000000003', 'ACTIVE', '2026-10-01T09:10:00Z'),
('a1b2c3d4-0b8e-4c55-9a51-000000000004', '9b2f0c4a-6d1e-4f2a-8c3b-000000000201', '6f1d4c1e-0b8e-4c55-9a51-000000000007', 'ACTIVE', '2026-10-01T09:15:00Z'),
('a1b2c3d4-0b8e-4c55-9a51-000000000005', '9b2f0c4a-6d1e-4f2a-8c3b-000000000202', '6f1d4c1e-0b8e-4c55-9a51-000000000008', 'ACTIVE', '2026-10-01T09:20:00Z');
