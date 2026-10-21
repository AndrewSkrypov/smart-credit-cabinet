-- Synthetic demo accounts for checking the end-to-end scenario; the shared password "Demo2026"
-- is published in the README. With DEMO_ACCOUNTS=false they are created blocked.
-- ИНН of demo companies start with "00": no Russian region has that code, so they can never
-- belong to a real business.
insert into companies (id, inn, name, created_at) values
('6f1d4c1e-0b8e-4c55-9a51-000000000001', '000100000004', 'ИП Котова Ирина Сергеевна', '2026-10-01T09:00:00Z');

insert into users (id, full_name, email, role, status, company_id, can_sign, password_hash, created_at) values
('9b2f0c4a-6d1e-4f2a-8c3b-000000000001', 'Ирина Котова', 'client@demo.test', 'CLIENT',
    case when '${demo-accounts}' = 'true' then 'ACTIVE' else 'BLOCKED' end,
    '6f1d4c1e-0b8e-4c55-9a51-000000000001', true,
    '$2a$10$QAS4QGFj5n0t4bOUdzhGq.2R1di2Knm8WRphHGDFiVElZ/NKZBTCO', '2026-10-01T09:00:00Z'),
('9b2f0c4a-6d1e-4f2a-8c3b-000000000101', 'Алексей Морозов', 'manager@demo.test', 'MANAGER',
    case when '${demo-accounts}' = 'true' then 'ACTIVE' else 'BLOCKED' end,
    null, false, '$2a$10$QAS4QGFj5n0t4bOUdzhGq.2R1di2Knm8WRphHGDFiVElZ/NKZBTCO', '2026-10-01T09:00:00Z'),
('9b2f0c4a-6d1e-4f2a-8c3b-000000000102', 'Елена Власова', 'underwriter@demo.test', 'UNDERWRITER',
    case when '${demo-accounts}' = 'true' then 'ACTIVE' else 'BLOCKED' end,
    null, false, '$2a$10$QAS4QGFj5n0t4bOUdzhGq.2R1di2Knm8WRphHGDFiVElZ/NKZBTCO', '2026-10-01T09:00:00Z'),
('9b2f0c4a-6d1e-4f2a-8c3b-000000000103', 'Дмитрий Гусев', 'risk@demo.test', 'RISK_ADMIN',
    case when '${demo-accounts}' = 'true' then 'ACTIVE' else 'BLOCKED' end,
    null, false, '$2a$10$QAS4QGFj5n0t4bOUdzhGq.2R1di2Knm8WRphHGDFiVElZ/NKZBTCO', '2026-10-01T09:00:00Z');
