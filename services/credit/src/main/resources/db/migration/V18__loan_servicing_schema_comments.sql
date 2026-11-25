-- Columns introduced by the post-disbursement schema in V15.
comment on column loan_accounts.id is 'Идентификатор кредитного счёта';
comment on column loan_accounts.application_id is 'Заявка, по которой открыт счёт после выдачи';
comment on column loan_accounts.company_id is 'Компания, которой принадлежит счёт';
comment on column loan_accounts.contract_number is 'Номер подписанного кредитного договора';
comment on column loan_accounts.approved_limit is 'Одобренный лимит в рублях';
comment on column loan_accounts.disbursed_amount is 'Накопленная сумма выдач в рублях';
comment on column loan_accounts.outstanding_principal is 'Остаток основного долга в рублях и копейках';
comment on column loan_accounts.rate is 'Годовая процентная ставка по подписанному договору';
comment on column loan_accounts.term_months is 'Срок кредитования в месяцах';
comment on column loan_accounts.revolving is 'Признак кредитной линии с дополнительными траншами';
comment on column loan_accounts.status is 'Состояние счёта ACTIVE или REPAID';
comment on column loan_accounts.disbursed_at is 'Дата и время открытия счёта при выдаче';
comment on column loan_accounts.maturity_date is 'Дата окончания кредитования по договору';
comment on column loan_accounts.repaid_at is 'Дата и время полного погашения';
comment on column loan_accounts.created_at is 'Дата и время создания записи счёта';
comment on column loan_accounts.updated_at is 'Дата и время последнего изменения счёта';

comment on column loan_tranches.id is 'Идентификатор транша';
comment on column loan_tranches.loan_account_id is 'Кредитный счёт, по которому выдан транш';
comment on column loan_tranches.tranche_number is 'Порядковый номер транша внутри счёта';
comment on column loan_tranches.amount is 'Выданная сумма транша в рублях';
comment on column loan_tranches.purpose is 'Назначение транша, указанное клиентом';
comment on column loan_tranches.disbursed_at is 'Дата и время выдачи транша';

comment on column loan_payments.id is 'Идентификатор строки графика или внесённого платежа';
comment on column loan_payments.loan_account_id is 'Кредитный счёт, к которому относится платёж';
comment on column loan_payments.payment_number is 'Порядковый номер платежа внутри счёта';
comment on column loan_payments.due_date is 'Плановая дата платежа';
comment on column loan_payments.payment_amount is 'Общая сумма платежа в рублях и копейках';
comment on column loan_payments.principal_amount is 'Часть платежа в погашение основного долга';
comment on column loan_payments.interest_amount is 'Часть платежа в погашение процентов';
comment on column loan_payments.remaining_balance is 'Остаток основного долга после платежа по графику';
comment on column loan_payments.status is 'Состояние платежа SCHEDULED или PAID';
comment on column loan_payments.paid_at is 'Дата и время внесения платежа';
