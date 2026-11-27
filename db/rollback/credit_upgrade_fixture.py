"""Synthetic V23 rows and immutable upgrade assertions for the real restore gate.

Only SELECT statements are used for verification. No application/runtime role is
created: this fixture tests migrations, not runtime grants or remote cutover.
"""

import json
from pathlib import Path


SEED_VERSION = 23
STAMP = "2026-01-02T03:04:05+00:00"
CHECKSUM = "f3" * 32


def identifier(number):
    return f"73000000-0000-4000-8000-{number:012d}"


PENDING_APP, RUNNING_APP, SUBMITTED_APP, SERVICING_APP = map(identifier, range(1, 5))
PENDING_JOB, RUNNING_JOB, DONE_JOB, SUBMITTED_JOB = map(identifier, range(11, 15))
DOCUMENT, LOAN, TRANCHE, PAYMENT = map(identifier, range(21, 25))
ACTOR = identifier(99)
DECISION, OFFER, HISTORICAL_ARTIFACT, SIGNED_ARTIFACT = map(identifier, range(31, 35))


class FrozenJson(str):
    """Exact model serialization, including the scale of monetary JSON numbers."""


# Compact JSON follows ArtifactInput/Calculation/ScheduleRow declaration order.
# It represents LoanMath.calculate(1000000, 15.00, 12, true); trailing file LF is
# not part of the canonical bytes hashed by GeneratedArtifacts.
ARTIFACT_INPUT = FrozenJson(Path(__file__).with_name("credit-upgrade-artifact.json").read_text(encoding="utf-8").rstrip("\n"))
SIGNED_ARTIFACT_INPUT = FrozenJson(Path(__file__).with_name("credit-upgrade-artifact-signed.json").read_text(encoding="utf-8").rstrip("\n"))
ARTIFACT_INPUT_HASH = "18e4150517f209af5016584fb38867bcdd61c8716c326f6b609aa911a480bdc6"
SIGNED_ARTIFACT_INPUT_HASH = "5253826f83d270dc6f00441112a33643c74dc9aba60c01f4d308697ed977907b"


def application(app_id, status, version):
    return dict(id=app_id, company_id=app_id, company_inn="0000000001", company_name="Synthetic upgrade",
                legal_form="OOO", created_by=ACTOR, product_code="WORKING_CAPITAL", purpose="WORKING_CAPITAL",
                amount=1000000, term_months=12, status=status, created_at=STAMP, updated_at=STAMP,
                submitted_at=STAMP, status_changed_at=STAMP, version=version, product_version=1,
                product_catalog_snapshot={"WORKING_CAPITAL": 1})


def job(job_id, app_id, status, attempts):
    return dict(id=job_id, application_id=app_id, step="ENRICHMENT", status=status, attempts=attempts,
                run_at=STAMP, created_at=STAMP, updated_at=STAMP, last_error=None)


def artifact(artifact_id, template_version, input_json, input_hash):
    return dict(id=artifact_id, application_id=SERVICING_APP, offer_id=OFFER, kind="CONTRACT_PDF", phase="FINAL",
                template_version=template_version, input_hash=input_hash, input_json=input_json,
                title="Synthetic frozen contract", file_name="synthetic-v23-contract.pdf", content_type="application/pdf",
                stored_name=f"generated-{artifact_id}.pdf", storage_backend="LOCAL", status="READY", size_bytes=4321,
                checksum="f4" * 32, attempts=0, next_attempt_at=STAMP, error_code=None,
                created_at=STAMP, updated_at=STAMP, ready_at=STAMP)


# Explicit columns are available in V23. Their values are checked after every later
# migration, even when the new schema adds columns to these tables.
ROWS = (
    ("applications", application(PENDING_APP, "ENRICHMENT", 7)),
    ("applications", application(RUNNING_APP, "ENRICHMENT", 11)),
    ("applications", application(SUBMITTED_APP, "SUBMITTED", 13)),
    ("applications", dict(application(SERVICING_APP, "DISBURSED", 17), number=73004)),
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
    ("decisions", dict(id=DECISION, application_id=SERVICING_APP, rule_set_version=1, decided_by_type="SYSTEM",
                       outcome="APPROVE", inputs={"fixture": "v23-restore"}, created_at=STAMP)),
    ("offers", dict(id=OFFER, application_id=SERVICING_APP, decision_id=DECISION, kind="APPROVED",
                    product_code="WORKING_CAPITAL", product_version=1, amount=1000000, term_months=12,
                    rate=15, monthly_payment="112500.00", total_payment="1089696.31", valid_until="2026-01-03T03:04:05Z",
                    status="ACCEPTED", created_at=STAMP, decided_at=STAMP)),
    ("generated_artifacts", artifact(HISTORICAL_ARTIFACT, 1, ARTIFACT_INPUT, ARTIFACT_INPUT_HASH)),
    ("generated_artifacts", artifact(SIGNED_ARTIFACT, 2, SIGNED_ARTIFACT_INPUT, SIGNED_ARTIFACT_INPUT_HASH)),
)


def literal(value):
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, FrozenJson):
        return literal(str(value)) + "::jsonb"
    if isinstance(value, dict):
        return literal(json.dumps(value, sort_keys=True)) + "::jsonb"
    return "'" + value.replace("'", "''") + "'"


def predicate(row):
    return " and ".join(
        f"{column}::text is not distinct from ({literal(value)})::text" if isinstance(value, FrozenJson)
        else f"{column} is not distinct from {literal(value)}" for column, value in row.items()
    )


def insert_sql():
    return ";\n".join(f"insert into {table} ({', '.join(row)}) values "
                       f"({', '.join(map(literal, row.values()))})" for table, row in ROWS) + ";"


def checks(version):
    if version < SEED_VERSION:
        return []
    result = []
    for index, (table, row) in enumerate(ROWS):
        result.append((f"v23_{table}_{index}", f"select count(*) from {table} where {predicate(row)}", "1"))
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
        for label, input_json, input_hash in (("historical", ARTIFACT_INPUT, ARTIFACT_INPUT_HASH),
                                              ("signed", SIGNED_ARTIFACT_INPUT, SIGNED_ARTIFACT_INPUT_HASH)):
            reference = dict(application_id=SERVICING_APP, offer_id=OFFER, phase="FINAL",
                             input_hash=input_hash, input_json=input_json)
            result.append((f"v26_{label}_artifact_reference", "select count(*) from document_artifact_references where "
                           + predicate(reference), "1"))
        result.append(("v26_exact_artifact_references", "select count(*) from document_artifact_references", "2"))
    return result


def verify(sql, database, version):
    verified = []
    for name, query, expected in checks(version):
        actual = sql(database, query)
        assert actual == expected, f"credit V{version} {name}: expected {expected}, got {actual!r}"
        verified.append(name)
    return verified
