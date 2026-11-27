"""Synthetic V23 rows and immutable upgrade assertions for the real restore gate.

Only SELECT statements are used for verification. No application/runtime role is
created: this fixture tests migrations, not runtime grants or remote cutover.
"""

import json


SEED_VERSION = 23
STAMP = "2026-01-02T03:04:05+00:00"
CHECKSUM = "f3" * 32


def identifier(number):
    return f"73000000-0000-4000-8000-{number:012d}"


PENDING_APP, RUNNING_APP, SUBMITTED_APP, SERVICING_APP = map(identifier, range(1, 5))
PENDING_JOB, RUNNING_JOB, DONE_JOB, SUBMITTED_JOB = map(identifier, range(11, 15))
DOCUMENT, LOAN, TRANCHE, PAYMENT = map(identifier, range(21, 25))
ACTOR = identifier(99)


def application(app_id, status, version):
    return dict(id=app_id, company_id=app_id, company_inn="0000000001", company_name="Synthetic upgrade",
                legal_form="OOO", created_by=ACTOR, product_code="WORKING_CAPITAL", purpose="WORKING_CAPITAL",
                amount=1000000, term_months=12, status=status, created_at=STAMP, updated_at=STAMP,
                submitted_at=STAMP, status_changed_at=STAMP, version=version, product_version=1,
                product_catalog_snapshot={"fixture": "v23-restore", "code": "WORKING_CAPITAL", "version": 1})


def job(job_id, app_id, status, attempts):
    return dict(id=job_id, application_id=app_id, step="ENRICHMENT", status=status, attempts=attempts,
                run_at=STAMP, created_at=STAMP, updated_at=STAMP, last_error=None)


# Explicit columns are available in V23. Their values are checked after every later
# migration, even when the new schema adds columns to these tables.
ROWS = (
    ("applications", application(PENDING_APP, "ENRICHMENT", 7)),
    ("applications", application(RUNNING_APP, "ENRICHMENT", 11)),
    ("applications", application(SUBMITTED_APP, "SUBMITTED", 13)),
    ("applications", application(SERVICING_APP, "DISBURSED", 17)),
    ("pipeline_jobs", job(PENDING_JOB, PENDING_APP, "PENDING", 0)),
    ("pipeline_jobs", job(RUNNING_JOB, RUNNING_APP, "RUNNING", 2)),
    ("pipeline_jobs", job(DONE_JOB, PENDING_APP, "DONE", 1)),
    ("pipeline_jobs", job(SUBMITTED_JOB, SUBMITTED_APP, "PENDING", 0)),
    ("documents", dict(id=DOCUMENT, application_id=SUBMITTED_APP, type="BANK_STATEMENT",
                       file_name="synthetic-v23-statement.pdf", stored_name="synthetic-v23-statement",
                       size_bytes=1234, uploaded_by=ACTOR, uploaded_at=STAMP, deleted_at=None,
                       context="BUSINESS", storage_backend="LOCAL", checksum_sha256=CHECKSUM,
                       private_to_bank=True)),
    ("loan_accounts", dict(id=LOAN, application_id=SERVICING_APP, company_id=SERVICING_APP,
                           contract_number="SYNTHETIC-V23", approved_limit=1000000, disbursed_amount=200000,
                           outstanding_principal=180000, rate=15, term_months=12, revolving=True,
                           status="ACTIVE", disbursed_at=STAMP, maturity_date="2027-01-02", repaid_at=None,
                           created_at=STAMP, updated_at=STAMP)),
    ("loan_tranches", dict(id=TRANCHE, loan_account_id=LOAN, tranche_number=1, amount=200000,
                           purpose="Synthetic restore tranche", disbursed_at=STAMP)),
    ("loan_payments", dict(id=PAYMENT, loan_account_id=LOAN, payment_number=1, due_date="2026-02-02",
                           payment_amount=22000, principal_amount=20000, interest_amount=2000,
                           remaining_balance=180000, status="PAID", paid_at=STAMP)),
    ("payment_reserves", dict(loan_id=LOAN, version=2, opening_balance=5000, expected_receipt_amount=20000,
                              payable_amount=22000, target_buffer=3000, control_date="2026-01-30",
                              payment_date="2026-02-02", receipt_status="RECEIVED", received_amount=20000,
                              confirmed_version=None, drawn_tranche_id=None, drawn_amount=None,
                              confirmed_by=None, confirmed_at=None, updated_by=ACTOR, updated_at=STAMP)),
    ("payment_reserve_receipt_events", dict(loan_id=LOAN, event_id="synthetic-v23-receipt",
                                            receipt_status="RECEIVED", reported_amount=20000,
                                            plan_version=2, actor_id=ACTOR, created_at=STAMP)),
)


def literal(value):
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, dict):
        return literal(json.dumps(value, sort_keys=True)) + "::jsonb"
    return "'" + value.replace("'", "''") + "'"


def insert_sql():
    return ";\n".join(f"insert into {table} ({', '.join(row)}) values "
                       f"({', '.join(map(literal, row.values()))})" for table, row in ROWS) + ";"


def checks(version):
    if version < SEED_VERSION:
        return []
    result = []
    for index, (table, row) in enumerate(ROWS):
        predicate = " and ".join(f"{column} is not distinct from {literal(value)}" for column, value in row.items())
        result.append((f"v23_{table}_{index}", f"select count(*) from {table} where {predicate}", "1"))
    if version >= 24:
        result.append(("v24_local_cycles", f"""select count(*) from credit_assessment_cycle
            where (cycle_id, application_id, application_version) in
            (('{PENDING_JOB}'::uuid, '{PENDING_APP}'::uuid, 7),
             ('{RUNNING_JOB}'::uuid, '{RUNNING_APP}'::uuid, 11))
            and stream is null and request is null and result is null and accept_until is null
            and received_at is null and score_version is null and closed_at is null""", "2"))
        result.append(("v24_exact_cycle_population", "select count(*) from credit_assessment_cycle", "2"))
        result.append(("v24_no_remote_requests", "select count(*) from assessment_exchange_outbox", "0"))
    if version >= 25:
        result.extend((
            ("v25_local_writer", """select count(*) from servicing_writer_fence
                where singleton and writer='LOCAL' and epoch=1 and manifest_sha256 is null""", "1"),
            ("v25_single_writer", "select count(*) from servicing_writer_fence", "1"),
            ("v25_no_remote_open", "select count(*) from servicing_open_outbox", "0"),
            ("v25_no_remote_authority", "select count(*) from servicing_opened_commands", "0"),
        ))
    if version >= 26:
        result.extend((
            ("v26_local_documents", "select count(*) from document_ownership where singleton and owner='LOCAL' and epoch=1", "1"),
            ("v26_single_documents_owner", "select count(*) from document_ownership", "1"),
            ("v26_reference_backfill", f"""select count(*) from document_references
                where document_id='{DOCUMENT}' and application_id='{SUBMITTED_APP}' and created_at is not null""", "1"),
            ("v26_exact_reference_population", "select count(*) from document_references", "1"),
            ("v26_no_documents_outages", f"""select count(*) from pipeline_jobs
                where id in ('{PENDING_JOB}', '{RUNNING_JOB}', '{DONE_JOB}', '{SUBMITTED_JOB}')
                and documents_outages=0""", "4"),
        ))
    return result


def verify(sql, database, version):
    verified = []
    for name, query, expected in checks(version):
        actual = sql(database, query)
        assert actual == expected, f"credit V{version} {name}: expected {expected}, got {actual!r}"
        verified.append(name)
    return verified
