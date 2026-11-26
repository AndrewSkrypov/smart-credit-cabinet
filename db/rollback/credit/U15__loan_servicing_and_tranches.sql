-- Reverse V15__loan_servicing_and_tranches.sql (rehearsal / isolated DB only).
drop index if exists idx_loan_payments_loan;
drop table if exists loan_payments;

drop index if exists idx_loan_tranches_loan;
drop table if exists loan_tranches;

drop index if exists idx_loan_accounts_company;
drop table if exists loan_accounts;
