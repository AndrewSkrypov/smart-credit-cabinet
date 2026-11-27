-- Revert the column comments added by V18 on a disposable schema.
comment on column loan_accounts.id is null;
comment on column loan_accounts.application_id is null;
comment on column loan_accounts.company_id is null;
comment on column loan_accounts.contract_number is null;
comment on column loan_accounts.approved_limit is null;
comment on column loan_accounts.disbursed_amount is null;
comment on column loan_accounts.outstanding_principal is null;
comment on column loan_accounts.rate is null;
comment on column loan_accounts.term_months is null;
comment on column loan_accounts.revolving is null;
comment on column loan_accounts.status is null;
comment on column loan_accounts.disbursed_at is null;
comment on column loan_accounts.maturity_date is null;
comment on column loan_accounts.repaid_at is null;
comment on column loan_accounts.created_at is null;
comment on column loan_accounts.updated_at is null;

comment on column loan_tranches.id is null;
comment on column loan_tranches.loan_account_id is null;
comment on column loan_tranches.tranche_number is null;
comment on column loan_tranches.amount is null;
comment on column loan_tranches.purpose is null;
comment on column loan_tranches.disbursed_at is null;

comment on column loan_payments.id is null;
comment on column loan_payments.loan_account_id is null;
comment on column loan_payments.payment_number is null;
comment on column loan_payments.due_date is null;
comment on column loan_payments.payment_amount is null;
comment on column loan_payments.principal_amount is null;
comment on column loan_payments.interest_amount is null;
comment on column loan_payments.remaining_balance is null;
comment on column loan_payments.status is null;
comment on column loan_payments.paid_at is null;
