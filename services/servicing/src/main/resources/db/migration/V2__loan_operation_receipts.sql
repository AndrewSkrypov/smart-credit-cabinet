create table loan_operations (
    loan_account_id uuid not null references loan_accounts(id) on delete cascade,
    operation_key uuid not null,
    fingerprint varchar(64) not null,
    result jsonb not null,
    created_at timestamptz not null default now(),
    primary key (loan_account_id, operation_key)
);

create trigger loan_operations_writer_fence before insert or update or delete on loan_operations
    for each row execute function enforce_servicing_writer();

do $$ begin
    if (select writer from servicing_writer_fence where singleton) is distinct from 'REMOTE'
       and to_regrole('servicing_app') is not null then
        revoke select,insert,update,delete on loan_operations from servicing_app;
    end if;
end $$;

comment on table loan_operations is 'Выполненные платежи и выдачи траншей для безопасного повтора запроса';
comment on column loan_operations.loan_account_id is 'Кредитный счёт, к которому относится операция';
comment on column loan_operations.operation_key is 'Ключ операции, переданный клиентом';
comment on column loan_operations.fingerprint is 'SHA-256 от типа и параметров запроса';
comment on column loan_operations.result is 'Снимок кредитного счёта после первого выполнения';
comment on column loan_operations.created_at is 'Время первого выполнения операции';
