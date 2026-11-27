create table loan_operations (
    loan_account_id uuid not null references loan_accounts(id) on delete cascade,
    operation_key uuid not null,
    fingerprint varchar(64) not null,
    result jsonb not null,
    primary key (loan_account_id, operation_key)
);

comment on table loan_operations is 'Результаты выполненных платежей и выдач траншей для безопасного повтора';
comment on column loan_operations.loan_account_id is 'Кредитный счёт, к которому относится операция';
comment on column loan_operations.operation_key is 'Ключ операции, переданный клиентом';
comment on column loan_operations.fingerprint is 'SHA-256 от типа и параметров запроса';
comment on column loan_operations.result is 'Снимок кредитного счёта после первого успешного выполнения';
