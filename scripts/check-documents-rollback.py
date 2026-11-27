#!/usr/bin/env python3
"""Exercise U26 through psql stdin, including rollback on a missing legacy row."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
CHAT = ('message_attachments', 'application_chat_upload_keys', 'application_chat_audit')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pg-bin', type=Path, required=True)
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--user', required=True)
    parser.add_argument('--database', default='cabinet')
    parser.add_argument('--rollback-sql', type=Path, default=ROOT / 'db/rollback/credit/U26__documents_boundary.sql')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    schema = 'documents_rollback_' + uuid4().hex
    assert re.fullmatch(r'documents_rollback_[0-9a-f]{32}', schema)
    body = args.rollback_sql.read_text()
    env = {key: os.environ[key] for key in ('PATH', 'SYSTEMROOT', 'LD_LIBRARY_PATH', 'PGPASSWORD') if key in os.environ}
    env.update(PGCLIENTENCODING='UTF8', PGCONNECT_TIMEOUT='5')
    command = [str(args.pg_bin / 'psql'), '-X', '-qAt', '-v', 'ON_ERROR_STOP=1',
               '-v', 'VERBOSITY=verbose', '-h', '127.0.0.1', '-p', str(args.port), '-U', args.user, '-d', args.database]

    def psql(statement, expected=0):
        # No --single-transaction: the repository rollback itself must be atomic.
        result = subprocess.run(command, input=f'SET search_path TO "{schema}", pg_catalog;\n' + statement,
                                env=env, text=True, capture_output=True, timeout=30)
        if expected == 0 and result.returncode:
            raise RuntimeError('Unexpected psql failure (exit %d)' % result.returncode)
        return result

    def snapshot():
        rows = ','.join(f"'{table}',(select coalesce(jsonb_agg(to_jsonb(t)), '[]') from {table} t)"
                        for table in ('document_ownership', 'document_references', 'documents', 'generated_artifacts', *CHAT))
        query = f"""select jsonb_build_object('rows',jsonb_build_object({rows}),
          'constraints',(select coalesce(jsonb_agg(jsonb_build_array(c.conname,pg_get_constraintdef(c.oid)) order by c.conname),'[]')
            from pg_constraint c join pg_namespace n on n.oid=c.connamespace where n.nspname='{schema}'),
          'triggers',(select coalesce(jsonb_agg(pg_get_triggerdef(t.oid) order by t.tgname),'[]')
            from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace
            where n.nspname='{schema}' and not t.tgisinternal),
          'functions',(select coalesce(jsonb_agg(pg_get_functiondef(p.oid) order by p.proname),'[]')
            from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='{schema}'));"""
        return json.loads(psql(query).stdout)

    receipt = {'sourceSha': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
               'sourceTree': subprocess.check_output(['git', 'rev-parse', 'HEAD^{tree}'], cwd=ROOT, text=True).strip(),
               'rollbackSha256': hashlib.sha256(body.encode()).hexdigest(), 'schema': schema, 'status': 'FAIL'}
    created = False
    try:
        psql(f'create schema "{schema}";')
        created = True
        setup = """
        create table document_ownership(owner text); insert into document_ownership values ('LOCAL');
        create table documents(id uuid, application_id uuid, primary key(id,application_id));
        create table generated_artifacts(id uuid primary key);
        create table document_references(document_id uuid, application_id uuid, primary key(document_id,application_id));
        insert into document_references values ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002');
        create function assert_local_documents_writer() returns void language plpgsql as $$ begin return; end $$;
        create function fence_local_documents() returns trigger language plpgsql as $$ begin return new; end $$;
        create trigger documents_owner_fence before insert on documents for each row execute function fence_local_documents();
        create trigger artifacts_owner_fence before insert on generated_artifacts for each row execute function fence_local_documents();
        """
        for table in CHAT:
            setup += f"""create table {table}(id integer primary key, document_id uuid, application_id uuid,
              constraint {table}_business_reference_fkey foreign key(document_id,application_id)
              references document_references(document_id,application_id));
              insert into {table} select 1,document_id,application_id from document_references;"""
        psql(setup)
        before = snapshot()
        failed = psql(body, expected=1)
        assert failed.returncode != 0 and '23503' in failed.stderr and 'message_attachments' in failed.stderr, 'Missing legacy FK must reject U26'
        assert snapshot() == before, 'Failed U26 changed constraints, triggers, functions or rows'
        assert len(before['triggers']) == 2 and len(before['functions']) == 2
        assert sum('_business_reference_fkey' in name for name, _ in before['constraints']) == 3
        receipt['missingLegacyRow'] = '23503; connection closed; full schema/data snapshot unchanged'
        psql('insert into documents select document_id,application_id from document_references;')
        restored = snapshot()
        psql(body)
        after = snapshot()
        assert after['rows'] == restored['rows'], 'Successful U26 changed data'
        assert not after['triggers'] and not after['functions'], 'Ownership guards not removed'
        assert not any('_business_reference_fkey' in name for name, _ in after['constraints']), 'Business FK survived'
        assert sum('REFERENCES documents(' in definition for _, definition in after['constraints']) == 3, 'Legacy FK not restored'
        receipt.update(status='PASS', matchingLegacyRow='3 legacy FK; no business FK/ownership guards; data unchanged')
    except Exception as failure:
        receipt['failure'] = str(failure)
        raise
    finally:
        if created:
            # This exact generated schema is the only cleanup target; never public or a caller-provided name.
            assert re.fullmatch(r'documents_rollback_[0-9a-f]{32}', schema)
            psql(f'drop schema "{schema}" cascade;')
            remaining = psql(f"select count(*) from pg_namespace where nspname='{schema}';").stdout.strip()
            assert remaining == '0', 'Private schema cleanup incomplete'
        receipt['schemaRemoved'] = True
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(receipt, indent=2) + '\n')


if __name__ == '__main__':
    main()
