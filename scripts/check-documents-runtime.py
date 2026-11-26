#!/usr/bin/env python3
"""Source-bound own-DB, LOCAL migration, mTLS and Credit HTTP gate; no skipped tests."""
import argparse
from contextlib import closing
from datetime import datetime, timezone
import hashlib
import json
import importlib.util
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]

def free_port():
    with closing(socket.socket()) as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--pg-bin', default=os.environ.get('DOCUMENTS_TEST_PG_BIN', '/usr/lib/postgresql/16/bin'))
    parser.add_argument('--pg-share', default=os.environ.get('DOCUMENTS_TEST_PG_SHARE'))
    parser.add_argument('--postgres-image', default=os.environ.get('DOCUMENTS_TEST_POSTGRES_IMAGE'))
    parser.add_argument('--report-dir', default='ci-artifacts/documents-r3')
    parser.add_argument('--skip-build', action='store_true')
    parser.add_argument('--storage-backend', choices=('local','s3'), default='local')
    parser.add_argument('--s3-binary', default=os.environ.get('DOCUMENTS_TEST_S3_BINARY'))
    parser.add_argument('--s3-sha256', default=os.environ.get('DOCUMENTS_TEST_S3_BINARY_SHA256'))
    parser.add_argument('--s3-image', default=os.environ.get('DOCUMENTS_TEST_S3_IMAGE'))
    args = parser.parse_args()
    report = (ROOT / args.report_dir).resolve()
    report.mkdir(parents=True, exist_ok=True)
    scratch = Path(tempfile.mkdtemp(prefix='alpha-documents-runtime-'))
    processes = []
    handles = []
    container = None
    pg_started = False
    s3 = None
    scanner_stop = threading.Event()
    admin = 'documents_it_admin'
    admin_password = secrets.token_hex(24)
    credit_password = secrets.token_hex(24)
    own_password = secrets.token_hex(24)
    jwt = secrets.token_hex(32)
    pg_port, grpc_port, http_port, scanner_port = [free_port() for _ in range(4)]
    pg_bin = Path(args.pg_bin)
    base_env = os.environ.copy()
    for name in ('JWT_SECRET', 'SPRING_APPLICATION_JSON', 'PGOPTIONS', 'PGSERVICE', 'PGHOSTADDR', 'JAVA_TOOL_OPTIONS'):
        base_env.pop(name, None)
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java')
    gradle = os.environ.get('DOCUMENTS_TEST_GRADLE', './gradlew')
    def run(command, name, *, env=None, data=None):
        with (report / f'{name}.log').open('ab') as output:
            subprocess.run(command, cwd=ROOT, env=env or base_env, input=data, stdout=output, stderr=subprocess.STDOUT, check=True)
    def sql(db, statement, *, user=admin, password=admin_password, name='schema'):
        env = base_env | {'PGPASSWORD': password}
        run([str(pg_bin / 'psql'), '-X', '-w', '-v', 'ON_ERROR_STOP=1', '-h', '127.0.0.1', '-p', str(pg_port), '-U', user, '-d', db], name, env=env, data=statement.encode())
    def start(command, name, env):
        handle = (report / f'{name}.log').open('ab')
        handles.append(handle)
        process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=handle, stderr=subprocess.STDOUT)
        processes.append(process)
        return process
    def wait_port(port, process=None):
        deadline = time.monotonic() + 100
        while time.monotonic() < deadline:
            if process and process.poll() is not None:
                raise RuntimeError('Runtime exited before readiness; see bounded receipt logs')
            try:
                with socket.create_connection(('127.0.0.1', port), timeout=.2):
                    return
            except OSError:
                time.sleep(.2)
        raise RuntimeError('Runtime readiness deadline exceeded')
    def stop(process):
        if process.poll() is None:
            process.terminate()
            try: process.wait(timeout=15)
            except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
    def object_inventory(identity, db):
        if not s3: return []
        if db == 'documents': query = "select json_build_object('storedName',metadata->>'storedName','sizeBytes',metadata->'sizeBytes','checksum',metadata->>'checksum','backend',metadata->>'backend') from document_records union all select json_build_object('storedName',metadata->>'storedName','sizeBytes',metadata->'sizeBytes','checksum',metadata->>'checksum','backend',metadata->>'backend') from artifact_records where status='READY'"
        else: query = "select json_build_object('storedName',stored_name,'sizeBytes',size_bytes,'checksum',checksum_sha256,'backend',storage_backend) from documents union all select json_build_object('storedName',stored_name,'sizeBytes',size_bytes,'checksum',checksum,'backend',storage_backend) from generated_artifacts where status='READY'"
        output = subprocess.check_output([str(pg_bin/'psql'),'-X','-qAt','-h','127.0.0.1','-p',str(pg_port),'-U',admin,'-d',db,'-c',query],env=base_env|{'PGPASSWORD':admin_password})
        records=[json.loads(line) for line in output.splitlines() if line]
        for record in records:
            assert record['backend']=='S3'
            s3.verify(identity,record['storedName'],record['sizeBytes'],record['checksum'])
        return records
    try:
        if args.storage_backend == 's3':
            spec=importlib.util.spec_from_file_location('documents_s3_fixture',ROOT/'scripts/documents-s3-fixture.py')
            module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
            s3=module.S3Fixture(scratch,report,free_port,binary=args.s3_binary,binary_sha256=args.s3_sha256,image=args.s3_image)
        if not args.skip_build:
            run([gradle, '--no-daemon', '--max-workers=1', ':services:documents:bootJar', ':services:credit:bootJar', ':services:documents:testClasses'], 'build')
        if args.postgres_image:
            container = subprocess.check_output(['docker', 'run', '-d', '--rm', '--name', f'alpha-documents-{uuid.uuid4().hex[:10]}', '-p', '127.0.0.1::5432', '-e', 'POSTGRES_PASSWORD', '-e', f'POSTGRES_USER={admin}', '-e', 'POSTGRES_DB=postgres', args.postgres_image], env=base_env | {'POSTGRES_PASSWORD': admin_password}).decode().strip()
            address = subprocess.check_output(['docker', 'port', container, '5432/tcp']).decode().strip()
            pg_port = int(address.rsplit(':', 1)[1])
            wait_port(pg_port)
            # Use host psql for the isolated, verified image.
        else:
            (scratch / 'password').write_text(admin_password)
            command = [str(pg_bin / 'initdb'), '-D', str(scratch / 'db'), '--username', admin, '--no-locale', '--encoding=UTF8', '--auth-local=trust', '--auth-host=scram-sha-256', f'--pwfile={scratch / "password"}']
            if args.pg_share: command += ['-L', args.pg_share]
            run(command, 'postgres')
            run([str(pg_bin / 'pg_ctl'), '-D', str(scratch / 'db'), '-l', str(report / 'postgres-server.log'), '-o', f'-h 127.0.0.1 -p {pg_port} -k {scratch}', '-w', 'start'], 'postgres')
            pg_started = True
        deadline = time.monotonic() + 60
        while True:
            try:
                sql('postgres', 'select 1', name='database-ready')
                break
            except subprocess.CalledProcessError:
                if time.monotonic() > deadline: raise
                time.sleep(1)
        sql('postgres', f"create role credit_owner login password '{credit_password}'; create role credit_app login password '{credit_password}'; create role documents_owner login password '{own_password}'; create role documents_app login password '{own_password}';")
        sql('postgres', 'create database credit owner credit_owner; create database documents owner documents_owner;')
        for path in sorted((ROOT / 'services/credit/src/main/resources/db/migration').glob('V*.sql'), key=lambda p: int(p.name.split('__')[0][1:])):
            sql('credit', path.read_text(), user='credit_owner', password=credit_password)
        sql('documents', (ROOT / 'services/documents/src/main/resources/db/migration/V1__documents.sql').read_text(), user='documents_owner', password=own_password)
        for db, owner, app, password in [('credit', 'credit_owner', 'credit_app', credit_password), ('documents', 'documents_owner', 'documents_app', own_password)]:
            permissions = (ROOT / 'libs/platform-common/src/main/resources/cabinet/database-permissions.sql').read_text().replace('__OWNER__', owner).replace('__APP__', app)
            sql(db, permissions, user=owner, password=password)
            sql(db, f'revoke connect,temporary on database {db} from public; grant connect on database {db} to {app};', user=owner, password=password)
        sql('credit', (ROOT / 'services/documents/cutover/credit-grants.sql').read_text(), user='credit_owner', password=credit_password)
        sql('documents', (ROOT / 'services/documents/cutover/grants.sql').read_text(), user='documents_owner', password=own_password)
        application, company, hidden, visible, private = [str(uuid.uuid4()) for _ in range(5)]
        credit_files = scratch / 'credit-files'
        target_files = scratch / 'documents-files'
        credit_files.mkdir(); target_files.mkdir()
        body = b'%PDF-1.4\nMigration gate\n%%EOF'
        checksum = hashlib.sha256(body).hexdigest()
        for identity in (hidden, visible, private):
            if s3: s3.request('source','PUT',f'{identity}.pdf',body)
            else: (credit_files / f'{identity}.pdf').write_bytes(body)
        sql('credit', f"""
insert into applications(id,company_id,company_inn,company_name,legal_form,created_by,product_code,purpose,amount,term_months,status,created_at,updated_at,submitted_at,status_changed_at,agent_id,client_handoff_at,client_managed)
 values('{application}','{company}','000100000004','Документы','ENTREPRENEUR','{uuid.uuid4()}','WORKING_CAPITAL','CASH_GAP',123456,6,'SUBMITTED',now()-interval '2 days',now(),now()-interval '1 hour',now(),'{uuid.uuid4()}',now()-interval '1 day',true);
insert into financing_scenario_revisions(application_id,revision,product_version,snapshot) values('{application}',1,1,'{{}}');
insert into financing_scenario_publications(application_id,revision,status,document_ids,handed_terms) values('{application}',1,'AWAITING_CLIENT','["{visible}"]','{{}}');
""", user='credit_owner', password=credit_password)
        for identity in (hidden, visible, private):
            private_flag = identity == private
            uploaded = "now()" if private_flag else "now()-interval '1 day'"
            sql('credit', f"insert into documents(id,application_id,type,file_name,stored_name,size_bytes,uploaded_by,uploaded_at,context,storage_backend,checksum_sha256,private_to_bank) values('{identity}','{application}','{'BANK_STATEMENT' if private_flag else 'OTHER'}','proof.pdf','{identity}.pdf',{len(body)},'{uuid.uuid4()}',{uploaded},'BUSINESS','{args.storage_backend.upper()}',{'null' if identity == hidden else chr(39)+checksum+chr(39)},{str(private_flag).lower()});", user='credit_owner', password=credit_password)
        # Freeze the old writer before copying any bytes; migration itself cannot activate either writer.
        sql('credit', "update document_ownership set owner='FROZEN',epoch=2;", user='credit_owner', password=credit_password)
        sql('documents', 'update document_ownership set epoch=2;', user='documents_owner', password=own_password)
        migration_env = base_env | {'DOCUMENTS_DATABASE_URL': f'jdbc:postgresql://127.0.0.1:{pg_port}/documents', 'DOCUMENTS_DATABASE_USER': 'documents_owner', 'DOCUMENTS_DATABASE_PASSWORD': own_password,
            'DOCUMENTS_CREDIT_DATABASE_URL': f'jdbc:postgresql://127.0.0.1:{pg_port}/credit', 'DOCUMENTS_CREDIT_DATABASE_USER': 'credit_owner', 'DOCUMENTS_CREDIT_DATABASE_PASSWORD': credit_password,
            'DOCUMENTS_CREDIT_LOCAL_DIR': str(credit_files), 'DOCUMENTS_MIGRATION_DIRECTION': 'forward', 'DOCUMENTS_TLS_CERTIFICATE': '', 'DOCUMENTS_TLS_KEY': '', 'DOCUMENTS_TLS_CA': ''}
        if s3:
            migration_env |= {'SPRING_APPLICATION_JSON':json.dumps({'cabinet':{'storage':{'s3':s3.configuration('target')}}})}
            migration_env |= {'DOCUMENTS_CREDIT_S3_'+key.upper().replace('-','_'):value for key,value in s3.configuration('source').items()}
        migration_command = [java, '-Xmx192m', '-jar', 'services/documents/build/libs/app.jar', '--spring.flyway.enabled=false', '--cabinet.documents.grpc.enabled=false', '--cabinet.documents.migration.enabled=true', f'--cabinet.storage.local-dir={target_files}']
        run(migration_command, 'migration-forward', env=migration_env)
        run(migration_command, 'migration-forward-repeat', env=migration_env)
        certs = scratch / 'certs'; certs.mkdir()
        run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-days','2','-subj','/CN=Documents Gate CA','-keyout',str(certs/'ca.key'),'-out',str(certs/'ca.crt')], 'certificates')
        for name, san in [('server','DNS:localhost,IP:127.0.0.1'), ('credit','URI:spiffe://credit-cabinet/credit'), ('wrong','URI:spiffe://credit-cabinet/other')]:
            run(['openssl','req','-newkey','rsa:2048','-nodes','-subj',f'/CN={name}','-keyout',str(certs/f'{name}.key'),'-out',str(certs/f'{name}.csr')], 'certificates')
            (certs/f'{name}.ext').write_text(f'subjectAltName={san}\nextendedKeyUsage=serverAuth,clientAuth\n')
            run(['openssl','x509','-req','-days','2','-in',str(certs/f'{name}.csr'),'-CA',str(certs/'ca.crt'),'-CAkey',str(certs/'ca.key'),'-CAcreateserial','-extfile',str(certs/f'{name}.ext'),'-out',str(certs/f'{name}.crt')], 'certificates')
        def scanner():
            with closing(socket.socket()) as server:
                server.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1); server.bind(('127.0.0.1',scanner_port)); server.listen(4); server.settimeout(.2)
                while not scanner_stop.is_set():
                    try: connection,_ = server.accept()
                    except socket.timeout: continue
                    with connection:
                        connection.settimeout(5)
                        command = bytearray()
                        while not command.endswith(b'\0'):
                            chunk = connection.recv(1)
                            if not chunk: break
                            command.extend(chunk)
                        if command == b'zVERSION\0': connection.sendall(('ClamAV gate/1/'+datetime.now(timezone.utc).strftime('%a %b %d %H:%M:%S %Y')+'\0').encode())
                        elif command == b'zINSTREAM\0':
                            content = bytearray()
                            def read_exact(count):
                                result=bytearray()
                                while len(result)<count:
                                    chunk=connection.recv(count-len(result))
                                    if not chunk: raise RuntimeError('Incomplete synthetic scanner request')
                                    result.extend(chunk)
                                return result
                            while True:
                                count=int.from_bytes(read_exact(4),'big')
                                if not count: break
                                if len(content)+count > 21*1024*1024: raise RuntimeError('Scanner request limit exceeded')
                                content.extend(read_exact(count))
                            connection.sendall(b'stream: Gate.EICAR FOUND\0' if b'EICAR' in content else b'stream: OK\0')
        threading.Thread(target=scanner, daemon=True).start()
        sql('documents', "update document_ownership set owner='DOCUMENTS';", user='documents_owner', password=own_password)
        sql('credit', "update document_ownership set owner='REMOTE';", user='credit_owner', password=credit_password)
        own_env = base_env | {'DOCUMENTS_DATABASE_URL': f'jdbc:postgresql://127.0.0.1:{pg_port}/documents', 'DOCUMENTS_DATABASE_USER':'documents_app','DOCUMENTS_DATABASE_PASSWORD':own_password,
            'DOCUMENTS_GRPC_PORT':str(grpc_port),'DOCUMENTS_TLS_CERTIFICATE':str(certs/'server.crt'),'DOCUMENTS_TLS_KEY':str(certs/'server.key'),'DOCUMENTS_TLS_CA':str(certs/'ca.crt')}
        if s3: own_env['SPRING_APPLICATION_JSON']=json.dumps({'cabinet':{'storage':{'s3':s3.configuration('target')}}})
        own = start([java,'-Xmx256m','-jar','services/documents/build/libs/app.jar','--spring.flyway.enabled=false',f'--cabinet.storage.local-dir={target_files}',f'--cabinet.antivirus.port={scanner_port}',f'--cabinet.storage.mode={args.storage_backend}'], 'documents', own_env)
        wait_port(grpc_port, own)
        credit_env = base_env | {'DB_HOST':'127.0.0.1','DB_PORT':str(pg_port),'DB_NAME':'credit','DB_USER':'credit_app','DB_PASSWORD':credit_password,'SERVER_PORT':str(http_port),'JWT_SECRET':jwt,'PIPELINE_ENABLED':'false','OUTBOX_WORKER_ENABLED':'false','IDENTITY_URL':'','ANTIVIRUS_ENABLED':'false','STORAGE_BACKEND':args.storage_backend,'UPLOAD_DIR':str(credit_files)}
        credit = start([java,'-Xmx320m','-jar','services/credit/build/libs/app.jar','--spring.flyway.enabled=false','--cabinet.documents.mode=remote','--cabinet.documents.epoch=2','--cabinet.documents.grpc.host=127.0.0.1',f'--cabinet.documents.grpc.port={grpc_port}',f'--cabinet.documents.grpc.certificate={certs/"credit.crt"}',f'--cabinet.documents.grpc.key={certs/"credit.key"}',f'--cabinet.documents.grpc.ca={certs/"ca.crt"}'], 'credit', credit_env)
        wait_port(http_port, credit)
        test_env = base_env | {'DOCUMENTS_TEST_STORAGE_BACKEND':args.storage_backend.upper(),'DOCUMENTS_TEST_CERTS':str(certs),'DOCUMENTS_TEST_GRPC_PORT':str(grpc_port),'DOCUMENTS_TEST_APPLICATION':application,'DOCUMENTS_TEST_COMPANY':company,'DOCUMENTS_TEST_HIDDEN':hidden,'DOCUMENTS_TEST_VISIBLE':visible,'DOCUMENTS_TEST_PRIVATE':private,
            'DOCUMENTS_TEST_CREDIT_URL':f'http://127.0.0.1:{http_port}','DOCUMENTS_TEST_JWT_SECRET':jwt,'DOCUMENTS_TEST_DATABASE_BASE':f'jdbc:postgresql://127.0.0.1:{pg_port}','DOCUMENTS_TEST_ADMIN_USER':admin,'DOCUMENTS_TEST_ADMIN_PASSWORD':admin_password}
        run([gradle,'--no-daemon','--max-workers=1',':services:documents:documentsRuntimeTest','--rerun-tasks'], 'tests', env=test_env)
        xml_paths = sorted((ROOT/'services/documents/build/test-results/documentsRuntimeTest').glob('TEST-*.xml'))
        assert xml_paths
        totals = {key: sum(int(ET.parse(path).getroot().get(key,0)) for path in xml_paths) for key in ('tests','failures','errors','skipped')}
        assert totals['tests'] >= 3 and all(totals[key] == 0 for key in ('failures','errors','skipped')), totals
        for path in xml_paths: shutil.copy2(path, report/path.name)
        target_inventory = object_inventory('target','documents')
        stop(credit); stop(own)
        sql('documents', "update document_ownership set owner='FROZEN',epoch=3;", user='documents_owner', password=own_password)
        sql('credit', "update document_ownership set owner='FROZEN',epoch=3;", user='credit_owner', password=credit_password)
        # Prove current readers cannot return local data after remote ownership.
        sql('credit', "do $$ begin if not exists(select 1 from document_ownership where owner='FROZEN') then raise exception 'Reader fence absent'; end if; end $$;", user='credit_owner', password=credit_password)
        run(migration_command, 'migration-reverse', env=migration_env | {'DOCUMENTS_MIGRATION_DIRECTION':'reverse'})
        source_inventory = object_inventory('source','credit')
        assert not s3 or sorted(source_inventory,key=lambda row:row['storedName']) == sorted(target_inventory,key=lambda row:row['storedName'])
        sql('credit', "update document_ownership set owner='LOCAL';", user='credit_owner', password=credit_password)
        sql('credit', (ROOT/'db/rollback/credit/U26__documents_boundary.sql').read_text(), user='credit_owner', password=credit_password, name='rollback-compatibility')
        # V26 remains additive: all pre-existing metadata and attachment references survive.
        sql('credit', f"do $$ begin if (select count(*) from documents where application_id='{application}') < 4 then raise exception 'Reverse import lost rows'; end if; end $$;", user='credit_owner', password=credit_password, name='rollback-compatibility')
        revision = subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT).decode().strip()
        tree = subprocess.check_output(['git','rev-parse','HEAD^{tree}'],cwd=ROOT).decode().strip()
        jars = {service:hashlib.sha256((ROOT/f'services/{service}/build/libs/app.jar').read_bytes()).hexdigest() for service in ('documents','credit')}
        (report/'receipt.json').write_text(json.dumps({'sourceSha':revision,'sourceTree':tree,'jars':jars,'tests':totals,'migration':['forward','repeat','reverse','U26-data-preserving'],'storageBackend':args.storage_backend,'s3':({'provenance':s3.provenance,'targetPerFile':target_inventory,'reversePerFile':source_inventory} if s3 else {'status':'not-tested-in-LOCAL-profile'}),'scanner':'synthetic fresh clamd protocol; clean+reject; not AV efficacy evidence','environment':'native private PostgreSQL + actual standalone Documents/Credit JARs + mTLS'},ensure_ascii=False,indent=2)+'\n')
    finally:
        scanner_stop.set()
        if s3: s3.close()
        for process in reversed(processes): stop(process)
        for handle in handles: handle.close()
        if container: subprocess.run(['docker','rm','-fv',container],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        elif pg_started: subprocess.run([str(pg_bin/'pg_ctl'),'-D',str(scratch/'db'),'-m','fast','-w','stop'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        shutil.rmtree(scratch)

if __name__ == '__main__': main()
