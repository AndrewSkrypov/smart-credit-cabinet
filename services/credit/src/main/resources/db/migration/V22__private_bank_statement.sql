alter table documents
    add column private_to_bank boolean not null default false,
    add constraint ck_documents_private_bank_statement
        check (not private_to_bank or (type = 'BANK_STATEMENT' and context = 'BUSINESS'));

comment on column documents.private_to_bank is 'Выписка клиента доступна своей компании и сотрудникам банка по поданной заявке';
