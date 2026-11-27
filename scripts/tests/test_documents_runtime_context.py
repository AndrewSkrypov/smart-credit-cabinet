import importlib.util
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
import uuid
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from documents_runtime_context import RuntimeContext
from documents_runtime_context import start_ci_container


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


cleanup = load('documents_cleanup_context_test', 'documents-fixture-cleanup.py')
s3 = load('documents_s3_context_test', 'documents-s3-fixture.py')


class RuntimeContextTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='documents-context-', dir='/var/tmp')
        self.root = Path(self.directory.name)
        self.source = 'a' * 40
        self.env = {'CI_JOB_ID': '12345', 'CI_PROJECT_ID': '77', 'CI_PROJECT_DIR': str(self.root), 'CI_COMMIT_SHA': self.source}

    def tearDown(self):
        self.directory.cleanup()

    def test_shared_parent_reconstructed_without_shell_exports(self):
        context = RuntimeContext(self.root, self.source, self.env | {'TMPDIR': '/tmp/private', 'RUNNER_TEMP': '/tmp/private'})
        scratch = context.create_scratch()
        fresh = RuntimeContext(self.root, self.source, dict(self.env))
        self.assertEqual(scratch.parent, self.root / 'ci-artifacts/.job/documents-77-12345')
        self.assertTrue(fresh.permits(scratch, 'cabinet-documents-77-12345-s3'))
        self.assertFalse(fresh.permits(scratch, 'cabinet-documents-77-12346-s3'))
        self.assertFalse(fresh.permits(scratch.parent / 'other' / scratch.name, 'cabinet-documents-77-12345-s3'))

    def test_invalid_ci_context_never_falls_back_to_tmp(self):
        for delta in ({'CI_JOB_ID': 'bad'}, {'CI_PROJECT_DIR': '/tmp'}, {'CI_COMMIT_SHA': 'b' * 40}, {'CI_PROJECT_ID': ''}):
            with self.subTest(delta=delta), self.assertRaises(ValueError):
                RuntimeContext(self.root, self.source, self.env | delta)

    def test_shared_path_symlink_is_rejected(self):
        (self.root / 'ci-artifacts').symlink_to('/tmp', target_is_directory=True)
        with self.assertRaises(ValueError):
            RuntimeContext(self.root, self.source, self.env)

    def test_native_default_still_uses_temporary_directory(self):
        context = RuntimeContext(self.root, self.source, {})
        scratch = context.create_scratch()
        try:
            self.assertTrue(context.permits(scratch, 'cabinet-documents-native-test'))
            self.assertTrue(scratch.is_relative_to('/tmp') or scratch.is_relative_to('/var/tmp'))
        finally:
            shutil.rmtree(scratch)

    def test_missing_journal_without_current_scratch_is_noop_and_does_not_scan_other_job(self):
        foreign = self.root / 'ci-artifacts/.job/documents-77-999/alpha-documents-runtime-foreign'
        foreign.mkdir(parents=True)
        with patch.object(cleanup, 'ROOT', self.root), patch.dict(os.environ, self.env, clear=True), patch.object(cleanup.os, 'kill') as kill:
            cleanup.cleanup(self.root / 'absent.json', {'source': self.source, 'scope': 'cabinet-documents-77-12345-s3'})
            kill.assert_not_called()
        self.assertTrue(foreign.exists())

    def test_missing_journal_with_current_scratch_retains_data_and_fails(self):
        scratch = RuntimeContext(self.root, self.source, self.env).create_scratch()
        (scratch / 'synthetic-data').write_text('retain')
        with patch.object(cleanup, 'ROOT', self.root), patch.dict(os.environ, self.env, clear=True), patch.object(cleanup.os, 'kill') as kill:
            with self.assertRaisesRegex(RuntimeError, 'state uncertain, data retained'):
                cleanup.cleanup(self.root / 'absent.json', {'source': self.source, 'scope': 'cabinet-documents-77-12345-s3'})
            kill.assert_not_called()
        self.assertEqual((scratch / 'synthetic-data').read_text(), 'retain')

    def test_initial_native_plan_precedes_s3_constructor_and_hard_kill_retains_uncertain_data(self):
        source = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        env = self.env | {'CI_PROJECT_DIR': str(ROOT), 'CI_JOB_ID': str(uuid.uuid4().int)[:12],
                          'CI_COMMIT_SHA': source, 'JAVA_HOME': '/unused-no-java-started'}
        report = self.root / 'early-journal'
        script = r'''
import importlib.abc, importlib.util, json, os, runpy, signal, sys
from pathlib import Path
runtime, report = sys.argv[1:]
sys.path.insert(0, str(Path(runtime).parent))
original = importlib.util.spec_from_file_location
class EarlyS3:
    def __init__(self, scratch, report, *args, **kwargs):
        ledger = json.loads((report / 'native-processes.json').read_text())
        assert ledger['scratch'] == str(scratch) and ledger['processes'] == []
        assert ledger['nativeS3']['marker'] == f'-dir={scratch}/s3/data'
        assert json.loads((scratch / 'fixture-scope.json').read_text()) == {'scope':ledger['scope'], 'source':ledger['source']}
        (report / 'constructor-checkpoint.json').write_text(json.dumps(ledger))
        os.kill(os.getpid(), signal.SIGKILL)
class Loader(importlib.abc.Loader):
    def create_module(self, spec): return None
    def exec_module(self, module): module.S3Fixture = EarlyS3
def spec(name, path, *args, **kwargs):
    if Path(path).name == 'documents-s3-fixture.py': return importlib.util.spec_from_loader(name, Loader())
    return original(name, path, *args, **kwargs)
importlib.util.spec_from_file_location = spec
sys.argv = [runtime, '--skip-build', '--storage-backend', 's3', '--report-dir', report]
runpy.run_path(runtime, run_name='__main__')
'''
        result = subprocess.run([sys.executable, '-B', '-c', script, str(ROOT / 'scripts/check-documents-runtime.py'), str(report)],
                                env=env, capture_output=True, text=True, timeout=15)
        context = RuntimeContext(ROOT, source, env)
        scratch = None
        try:
            self.assertEqual(result.returncode, -signal.SIGKILL, result.stderr)
            checkpoint = json.loads((report / 'constructor-checkpoint.json').read_text())
            scratch = Path(checkpoint['scratch'])
            self.assertTrue(scratch.exists())
            cleaned = subprocess.run([sys.executable, '-B', str(ROOT / 'scripts/documents-fixture-cleanup.py'),
                                      str(report / 'native-processes.json'), '--scope', checkpoint['scope'], '--source', source],
                                     env=env, capture_output=True, text=True, timeout=15)
            self.assertNotEqual(cleaned.returncode, 0)
            self.assertIn('Native S3 startup identity unresolved', cleaned.stderr)
            self.assertTrue(scratch.exists())
        finally:
            if scratch and scratch.exists(): shutil.rmtree(scratch)
            if context.parent.exists():
                # Only this synthetic test job; also remove a failed pre-journal candidate.
                shutil.rmtree(context.parent)

    def test_native_s3_popen_before_register_retains_live_child_until_identity_is_recorded(self):
        source = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        env = self.env | {'CI_PROJECT_DIR': str(ROOT), 'CI_JOB_ID': str(uuid.uuid4().int)[:12], 'CI_COMMIT_SHA': source}
        context = RuntimeContext(ROOT, source, env)
        scratch = context.create_scratch()
        report = self.root / 'native-popen'; report.mkdir()
        scope = f"cabinet-documents-77-{env['CI_JOB_ID']}-s3"
        ledger = context.initialize_ledger(scratch, report, scope, native_s3=True)
        binary = self.root / 'synthetic-binary'; binary.write_bytes(b'not an engine')
        children = []
        original_popen = subprocess.Popen
        def popen(command, **kwargs):
            # This is a real own PID, created where S3Fixture calls Popen.
            marker = next(item for item in command if item.startswith('-dir='))
            child = original_popen([sys.executable, '-c', 'import time; time.sleep(60)', marker])
            children.append(child)
            return child
        def interrupted_registration(*args):
            raise InterruptedError('Synthetic parent interruption before PID journal write')
        fixture = s3.S3Fixture.__new__(s3.S3Fixture)
        try:
            with patch.object(s3.subprocess, 'Popen', popen), patch.object(s3.subprocess, 'check_output', return_value=b'version 4.55'):
                with self.assertRaises(InterruptedError):
                    fixture.__init__(scratch, report, lambda: 12345, binary=binary,
                                     binary_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(), register=interrupted_registration)
            child = children[0]
            self.assertEqual(json.loads((report / 'native-processes.json').read_text())['processes'], [])
            command = [sys.executable, '-B', str(ROOT / 'scripts/documents-fixture-cleanup.py'),
                       str(report / 'native-processes.json'), '--scope', scope, '--source', source]
            uncertain = subprocess.run(command, env=env, capture_output=True, text=True, timeout=15)
            self.assertNotEqual(uncertain.returncode, 0)
            self.assertIn('Native S3 startup identity unresolved', uncertain.stderr)
            self.assertTrue(scratch.exists())
            self.assertIsNone(child.poll())
            # A published exact identity restores the ordinary guarded stop path.
            ledger['processes'].append({'pid':child.pid, 'start':cleanup.identity(child.pid), 'marker':ledger['nativeS3']['marker']})
            (report / 'native-processes.json').write_text(json.dumps(ledger))
            cleaned = subprocess.run(command, env=env, capture_output=True, text=True, timeout=15)
            self.assertEqual(cleaned.returncode, 0, cleaned.stderr)
            child.wait(timeout=5)
            self.assertFalse(scratch.exists())
        finally:
            for child in children:
                if child.poll() is None:
                    child.terminate(); child.wait(timeout=5)
            if fixture.handle: fixture.handle.close()
            if scratch.exists(): shutil.rmtree(scratch)
            context.parent.rmdir()

    def test_actual_main_retains_ci_s3_and_pg_data_after_docker_timeout_with_live_consumer(self):
        runtime = load('documents_main_timeout_test', 'check-documents-runtime.py')
        source = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        original_run = subprocess.run
        for profile in ('s3', 'local'):
            with self.subTest(profile=profile):
                env = self.env | {'CI_PROJECT_DIR':str(ROOT), 'CI_JOB_ID':str(uuid.uuid4().int)[:12], 'CI_COMMIT_SHA':source,
                                  'JAVA_HOME':'/unused-no-java', 'CABINET_CI_DOCKER_HOST':'unix:///synthetic-cabinet-ci.sock',
                                  'CI_RUNNER_TAGS':'cabinet-ci'}
                context = RuntimeContext(ROOT, source, env)
                report = self.root / ('timeout-' + profile)
                arguments = ['--storage-backend','s3','--s3-image',s3.PINNED_IMAGE] if profile == 's3' else ['--postgres-image','synthetic-ci-postgres:test']
                children = []
                foreign = self.root / 'foreign-data'; foreign.write_text('unchanged')
                previous_handlers = {number:signal.getsignal(number) for number in (signal.SIGTERM,signal.SIGINT)}
                def docker(command, **kwargs):
                    if command[:2] == ['docker','info']:
                        return subprocess.CompletedProcess(command, 0, b'["cabinet-ci=true"]', b'')
                    if command[:2] == ['docker','run']:
                        ledger = json.loads((report/'native-processes.json').read_text())
                        scratch = Path(ledger['scratch'])
                        marker = f'-dir={scratch}/s3/data' if profile == 's3' else '-Dcabinet.documents.fixture.scope='+ledger['scope']
                        child = subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)',marker])
                        children.append((child,cleanup.identity(child.pid),marker))
                        (scratch/'bound-data').write_text('keep while Docker outcome is unknown')
                        raise subprocess.TimeoutExpired(command,45,stderr='synthetic-secret-not-for-logs')
                    return original_run(command, **kwargs)
                try:
                    with patch.dict(os.environ,env,clear=True), patch.object(sys,'argv',['runtime','--skip-build','--report-dir',str(report),*arguments]), patch.object(subprocess,'run',docker):
                        with self.assertRaisesRegex(RuntimeError,'Fixture Docker operation timed out'):
                            runtime.main()
                    retained = json.loads((report/'cleanup-retained.json').read_text())
                    scratch = Path(retained['scratch'])
                    self.assertTrue(scratch.exists())
                    self.assertEqual((scratch/'bound-data').read_text(),'keep while Docker outcome is unknown')
                    child, started, marker = children[0]
                    self.assertIsNone(child.poll())
                    self.assertEqual(foreign.read_text(),'unchanged')
                    # The supervisor owns this synthetic stand-in for a label-verified container.
                    self.assertEqual(cleanup.identity(child.pid),started)
                    self.assertIn(marker.encode(),Path(f'/proc/{child.pid}/cmdline').read_bytes().split(b'\0'))
                    child.terminate(); child.wait(timeout=5)
                    with patch.dict(os.environ,env,clear=True):
                        cleanup.cleanup(report/'native-processes.json',{'scope':retained['scope'],'source':source})
                    self.assertFalse(scratch.exists())
                    self.assertEqual(foreign.read_text(),'unchanged')
                finally:
                    for number, handler in previous_handlers.items(): signal.signal(number,handler)
                    for child, _, _ in children:
                        if child.poll() is None:
                            child.terminate(); child.wait(timeout=5)
                    if context.parent.exists(): shutil.rmtree(context.parent)

    def test_actual_main_retains_s3_and_pg_data_when_normal_finally_removal_is_unconfirmed(self):
        runtime=load('documents_main_rm_test','check-documents-runtime.py')
        source=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
        original_run=subprocess.run
        for profile in ('s3','local'):
            for removal in ('nonzero','timeout'):
                with self.subTest(profile=profile,removal=removal):
                    env=self.env|{'CI_PROJECT_DIR':str(ROOT),'CI_JOB_ID':str(uuid.uuid4().int)[:12],'CI_COMMIT_SHA':source,
                                  'JAVA_HOME':'/unused','CABINET_CI_DOCKER_HOST':'unix:///synthetic-cabinet-ci.sock',
                                  'CI_RUNNER_TAGS':'cabinet-ci','DOCUMENTS_TEST_GRADLE':'/synthetic-no-gradle'}
                    context=RuntimeContext(ROOT,source,env)
                    report=self.root/('remove-'+profile+'-'+removal)
                    children=[]
                    def consumer():
                        ledger=json.loads((report/'native-processes.json').read_text())
                        scratch=Path(ledger['scratch']); (scratch/'bound-data').write_text('retain')
                        marker='-Dcabinet.documents.fixture.scope='+ledger['scope']
                        child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)',marker])
                        children.append((child,cleanup.identity(child.pid),marker))
                    class FakeS3:
                        close=s3.S3Fixture.close  # exercise the real normal-close implementation.
                        def __init__(self,*args,**kwargs):
                            self.container='a'*64; self.report=report; self.docker_env={}; self.process=None; self.handle=None
                            consumer()
                    def docker(command,**kwargs):
                        if command[0]=='/synthetic-no-gradle': raise RuntimeError('Synthetic gate stop')
                        if command[:2]==['docker','info']: return subprocess.CompletedProcess(command,0,b'["cabinet-ci=true"]',b'')
                        if command[:2]==['docker','run']:
                            consumer(); return subprocess.CompletedProcess(command,0,'a'*64,'')
                        if command[:2]==['docker','logs']: return subprocess.CompletedProcess(command,0,'','')
                        if command[:2]==['docker','rm']:
                            self.assertEqual(command[-1],'a'*64)
                            if removal=='timeout': raise subprocess.TimeoutExpired(command,45)
                            return subprocess.CompletedProcess(command,1,'','')
                        return original_run(command,**kwargs)
                    arguments=['--storage-backend','s3','--s3-image',s3.PINNED_IMAGE] if profile=='s3' else ['--skip-build','--postgres-image','synthetic-postgres:test']
                    previous={number:signal.getsignal(number) for number in (signal.SIGTERM,signal.SIGINT)}
                    fake_spec=SimpleNamespace(loader=SimpleNamespace(exec_module=lambda module:None))
                    try:
                        with patch.dict(os.environ,env,clear=True), patch.object(sys,'argv',['runtime','--report-dir',str(report),*arguments]), patch.object(subprocess,'run',docker), patch.object(runtime.socket,'create_connection',side_effect=RuntimeError('Synthetic gate stop')), patch.object(runtime.importlib.util,'spec_from_file_location',return_value=fake_spec), patch.object(runtime.importlib.util,'module_from_spec',return_value=SimpleNamespace(S3Fixture=FakeS3)):
                            with self.assertRaisesRegex(RuntimeError,'Synthetic gate stop'): runtime.main()
                        retained=json.loads((report/'cleanup-retained.json').read_text())
                        scratch=Path(retained['scratch'])
                        self.assertEqual((scratch/'bound-data').read_text(),'retain')
                        child,started,marker=children[0]
                        self.assertIsNone(child.poll())
                        self.assertEqual(cleanup.identity(child.pid),started)
                        self.assertIn(marker.encode(),Path(f'/proc/{child.pid}/cmdline').read_bytes().split(b'\0'))
                        child.terminate(); child.wait(timeout=5)
                        with patch.dict(os.environ,env,clear=True): cleanup.cleanup(report/'native-processes.json',{'scope':retained['scope'],'source':source})
                        self.assertFalse(scratch.exists())
                    finally:
                        for number,handler in previous.items(): signal.signal(number,handler)
                        for child,_,_ in children:
                            if child.poll() is None: child.terminate(); child.wait(timeout=5)
                        if context.parent.exists(): shutil.rmtree(context.parent)

    def test_actual_main_native_sigterm_before_popen_returns_retains_live_consumer(self):
        runtime=load('documents_main_native_signal_test','check-documents-runtime.py')
        source=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
        env=self.env|{'CI_PROJECT_DIR':str(ROOT),'CI_JOB_ID':str(uuid.uuid4().int)[:12],'CI_COMMIT_SHA':source,'JAVA_HOME':'/unused'}
        context=RuntimeContext(ROOT,source,env)
        report=self.root/'native-signal'
        binary=self.root/'native-signal-binary'; binary.write_bytes(b'synthetic non-engine')
        children=[]
        original_popen=subprocess.Popen
        original_output=subprocess.check_output
        def output(command,**kwargs):
            return b'version 4.55' if command[0]==str(binary) else original_output(command,**kwargs)
        def popen(command,**kwargs):
            if command[0]!=str(binary): return original_popen(command,**kwargs)
            marker=next(value for value in command if value.startswith('-dir='))
            child=original_popen([sys.executable,'-c','import time; time.sleep(60)',marker])
            children.append((child,cleanup.identity(child.pid),marker))
            os.kill(os.getpid(),signal.SIGTERM)  # main's handler runs before Popen publishes self.process.
            raise AssertionError('SIGTERM handler must interrupt Popen return')
        previous={number:signal.getsignal(number) for number in (signal.SIGTERM,signal.SIGINT)}
        try:
            arguments=['runtime','--skip-build','--storage-backend','s3','--s3-binary',str(binary),'--s3-sha256',hashlib.sha256(binary.read_bytes()).hexdigest(),'--report-dir',str(report)]
            with patch.dict(os.environ,env,clear=True), patch.object(sys,'argv',arguments), patch.object(subprocess,'Popen',popen), patch.object(subprocess,'check_output',output):
                with self.assertRaises(SystemExit) as interrupted: runtime.main()
            self.assertEqual(interrupted.exception.code,143)
            ledger=json.loads((report/'native-processes.json').read_text())
            scratch=Path(ledger['scratch'])
            self.assertTrue(scratch.exists())
            self.assertEqual(ledger['processes'],[])
            self.assertTrue((report/'cleanup-retained.json').exists())
            child,started,marker=children[0]
            self.assertIsNone(child.poll())
            with patch.dict(os.environ,env,clear=True), self.assertRaisesRegex(RuntimeError,'Native S3 startup identity unresolved'):
                cleanup.cleanup(report/'native-processes.json',{'scope':ledger['scope'],'source':source})
            self.assertTrue(scratch.exists())
            self.assertEqual(cleanup.identity(child.pid),started)
            self.assertIn(marker.encode(),Path(f'/proc/{child.pid}/cmdline').read_bytes().split(b'\0'))
        finally:
            for number,handler in previous.items(): signal.signal(number,handler)
            for child,_,_ in children:
                if child.poll() is None: child.terminate(); child.wait(timeout=5)
            if context.parent.exists(): shutil.rmtree(context.parent)

    def test_fresh_cleanup_shell_stops_only_owned_identity_in_current_job(self):
        # Use the real checkout because a fresh helper independently derives ROOT.
        env = self.env | {'CI_PROJECT_DIR': str(ROOT), 'CI_JOB_ID': str(uuid.uuid4().int)[:12]}
        context = RuntimeContext(ROOT, self.source, env)
        scratch = context.create_scratch()
        scope = f"cabinet-documents-77-{env['CI_JOB_ID']}-s3"
        marker = '-Dcabinet.documents.fixture.scope=' + scope
        (scratch / 'fixture-scope.json').write_text(json.dumps({'scope': scope, 'source': self.source}))
        process = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)', marker])
        ledger = self.root / 'native-processes.json'
        ledger.write_text(json.dumps({'scope': scope, 'source': self.source, 'scratch': str(scratch),
                                     'processes': [{'pid': process.pid, 'start': cleanup.identity(process.pid), 'marker': marker}]}))
        try:
            with patch.object(cleanup, 'ROOT', ROOT), patch.dict(os.environ, env | {'CI_JOB_ID': '999'}, clear=True), patch.object(cleanup.os, 'kill') as kill:
                with self.assertRaises(ValueError):
                    cleanup.cleanup(ledger, {'scope': scope, 'source': self.source})
                kill.assert_not_called()
                self.assertTrue(scratch.exists())
                self.assertIsNone(process.poll())
            # No inherited RUNNER_TEMP/TMPDIR; equivalent to after_script's new shell.
            result = subprocess.run([sys.executable, '-B', str(ROOT / 'scripts/documents-fixture-cleanup.py'), str(ledger),
                                     '--scope', scope, '--source', self.source], env=env, capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stderr)
            process.wait(timeout=5)
            self.assertFalse(scratch.exists())
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=5)
            if scratch.exists():
                shutil.rmtree(scratch)
            context.parent.rmdir()

    def check_s3_readiness(self, corrupt=False):
        scratch = RuntimeContext(self.root, self.source, self.env).create_scratch()
        seen = []
        objects = {}
        def docker(command, **kwargs):
            for index, argument in enumerate(command):
                if argument == '--mount':
                    mount = command[index + 1]
                    source = Path(mount.split('source=', 1)[1].split(',', 1)[0])
                    self.assertTrue(source.exists())
                    self.assertTrue(source.is_relative_to(scratch))
                    seen.append(source)
            return subprocess.CompletedProcess(command, 0, 'a' * 64, '')
        def request(instance, identity, method, name, body=b'', *, bucket_identity=None):
            if identity != 'bootstrap' and bucket_identity and bucket_identity != identity:
                raise HTTPError('http://fixture', 403, 'denied', {}, None)
            if method == 'PUT' and name:
                objects[identity, name] = body
            value = objects.get((identity, name), b'')
            if corrupt and method == 'GET':
                value = b'corrupt'
            return {'content-length': str(len(value))}, value
        denied = HTTPError('http://fixture', 403, 'denied', {}, None)
        labels = {'cabinet.fixture':'documents-r3', 'cabinet.ci.job':'12345', 'cabinet.ci.project':'77',
                  'cabinet.source':self.source, 'cabinet.profile':'s3'}
        with patch.object(s3.subprocess, 'run', docker), patch.object(s3.S3Fixture, 'request', request), patch.object(s3.HTTP, 'open', side_effect=denied):
            fixture = s3.S3Fixture(scratch, self.root, lambda: 12345, image=s3.PINNED_IMAGE,
                                   docker_env={}, scope='cabinet-documents-77-12345-s3', labels=labels)
        self.assertEqual(len(seen), 4)
        self.assertIn('PUT-HEAD-GET-size-sha256-both-buckets', fixture.provenance['strictChecks'])
        self.assertEqual(len(objects), 2)

    def test_docker_mounts_exist_under_shared_job_before_strict_readiness(self):
        self.check_s3_readiness()

    def test_shared_scratch_does_not_weaken_readiness_checksum(self):
        with self.assertRaisesRegex(AssertionError, 'checksum or size mismatch'):
            self.check_s3_readiness(corrupt=True)


class ContainerStartTest(unittest.TestCase):
    def setUp(self):
        self.labels = {'cabinet.fixture':'documents-r3', 'cabinet.ci.job':'12345', 'cabinet.ci.project':'77',
                       'cabinet.source':'a'*40, 'cabinet.profile':'local', 'cabinet.resource':'postgres'}
        self.name = 'cabinet-documents-77-12345-local-postgres'
        self.image = 'cabinet-ci-12345-postgres:test'
        self.command = ['docker','run','-d','--name',self.name,'-p','127.0.0.1::5432',
                        '--mount','type=bind,source=/shared/fixture,target=/fixture,readonly', self.image]
        self.calls = []
        self.ports = []
        self.metadata = {'id':'a'*64, 'name':'/'+self.name, 'image':self.image, 'running':False, 'labels':self.labels}

    def docker(self, command, **kwargs):
        self.calls.append(command)
        if command[1] == 'run':
            publication = command[command.index('-p')+1]
            self.assertRegex(publication, r'^127\.0\.0\.1:[0-9]+:5432$')
            self.ports.append(int(publication.split(':')[1]))
            self.assertIn('type=bind,source=/shared/fixture,target=/fixture,readonly', command)
            if len(self.ports) > 1:
                return subprocess.CompletedProcess(command, 0, 'b'*64, '')
            return self.collision(command)
        if command[1] == 'inspect':
            self.assertEqual(command[-1], self.name)
            return subprocess.CompletedProcess(command, 0, json.dumps(self.metadata), '')
        self.assertEqual(command, ['docker','rm','a'*64])
        return subprocess.CompletedProcess(command, 0, '', '')

    def collision(self, command):
        return subprocess.CompletedProcess(command, 125, '', f'rootlesskit: listen tcp4 127.0.0.1:{self.ports[-1]}: bind: address already in use')

    def start(self):
        return start_ci_container(self.command, {'POSTGRES_PASSWORD':'synthetic-test-secret'},
                                  name=self.name,image=self.image,labels=self.labels,container_port=5432)

    def test_collision_removes_only_verified_stopped_id_then_retries_new_host_port(self):
        with patch.object(subprocess, 'run', self.docker):
            identity, port = self.start()
        self.assertEqual(identity, 'b'*64)
        self.assertEqual(len(set(self.ports)), 2)
        self.assertEqual(port, self.ports[-1])
        self.assertEqual([call[1] for call in self.calls], ['run','inspect','rm','run'])

    def test_foreign_running_wrong_image_or_name_never_removed_or_retried(self):
        for delta in ({'labels':self.labels|{'cabinet.ci.job':'999'}}, {'running':True}, {'image':'foreign'}, {'name':'/foreign'}):
            with self.subTest(delta=delta):
                self.metadata.update(delta)
                self.calls.clear(); self.ports.clear()
                with patch.object(subprocess, 'run', self.docker), self.assertRaisesRegex(RuntimeError, 'ownership/state mismatch'):
                    self.start()
                self.assertEqual([call[1] for call in self.calls], ['run','inspect'])
                self.setUp()

    def test_non_port_error_is_not_retried_and_stderr_is_not_exposed(self):
        def failure(command, **kwargs):
            self.calls.append(command)
            return subprocess.CompletedProcess(command, 125, '', 'permission denied: synthetic-test-secret')
        with patch.object(subprocess, 'run', failure), self.assertRaises(RuntimeError) as rejected:
            self.start()
        self.assertNotIn('synthetic-test-secret', str(rejected.exception))
        self.assertEqual(len(self.calls), 1)

    def test_three_collision_limit_retains_last_failed_container(self):
        original = self.docker
        def collide(command, **kwargs):
            result = original(command, **kwargs)
            return self.collision(command) if command[1] == 'run' else result
        with patch.object(subprocess, 'run', collide), self.assertRaisesRegex(RuntimeError, 'port collision'):
            self.start()
        self.assertEqual(len(set(self.ports)), 3)
        self.assertEqual([call[1] for call in self.calls].count('rm'), 2)

    def test_operation_timeout_does_not_expose_command_or_retry(self):
        with patch.object(subprocess, 'run', side_effect=subprocess.TimeoutExpired('synthetic-test-secret', 45)) as run:
            with self.assertRaises(RuntimeError) as rejected:
                self.start()
        self.assertNotIn('synthetic-test-secret', str(rejected.exception))
        self.assertEqual(run.call_count, 1)


class AfterScriptTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='documents-after-script-',dir='/var/tmp')
        self.root = Path(self.directory.name)
        for filename in ('ci/gitlab/quality/documents-cleanup.sh','ci/gitlab/quality/runner.sh',
                         'scripts/documents-fixture-cleanup.py','scripts/documents_runtime_context.py'):
            target = self.root/filename; target.parent.mkdir(parents=True,exist_ok=True)
            shutil.copyfile(ROOT/filename,target)
        self.source = 'a'*40
        binary = self.root/'bin'; binary.mkdir()
        self.mode = self.root/'mode'; self.mode.write_text('absent')
        self.calls = self.root/'calls'
        docker = binary/'docker'
        docker.write_text('#!'+sys.executable+'\n'+r'''
import os,sys
from pathlib import Path
root=Path(__file__).resolve().parent.parent
with (root/'calls').open('a') as output: output.write(' '.join(sys.argv[1:])+'\n')
mode=(root/'mode').read_text()
if sys.argv[1]=='info': print('cabinet-ci=true'); sys.exit(0)
if sys.argv[1]=='inspect':
    if sys.argv[-1].endswith('-s3-s3') and mode in ('rmerror','foreign'):
        job='999' if mode=='foreign' else '12345'
        print('a'*64+' documents-r3 77 '+job+' '+os.environ['CI_COMMIT_SHA']+' s3 s3'); sys.exit(0)
    print('Synthetic inspect connection failure',file=sys.stderr); sys.exit(1)
if sys.argv[1]=='rm': sys.exit(1)
if sys.argv[1:4]==['container','ls','-aq']:
    name=sys.argv[-1]
    assert name.startswith('name=^/cabinet-documents-77-12345-') and name.endswith('$')
    if name.endswith('-s3-s3$') and mode=='error': sys.exit(1)
    if name.endswith('-s3-s3$') and mode=='present': print('a'*64)
    sys.exit(0)
sys.exit(9)
''')
        docker.chmod(0o700)
        self.env = {'PATH':str(binary)+os.pathsep+os.defpath,'CI_PROJECT_DIR':str(self.root),'CI_JOB_ID':'12345',
                    'CI_PROJECT_ID':'77','CI_COMMIT_SHA':self.source,'CABINET_CI_DOCKER_HOST':'unix:///synthetic-cabinet-ci.sock'}
        self.context = RuntimeContext(self.root,self.source,self.env)
        self.foreign = self.root/'ci-artifacts/.job/documents-77-999/alpha-documents-runtime-foreign'
        self.foreign.mkdir(parents=True); (self.foreign/'keep').write_text('foreign unchanged')

    def tearDown(self):
        self.directory.cleanup()

    def shell(self):
        return subprocess.run(['bash',str(self.root/'ci/gitlab/quality/documents-cleanup.sh')],
                              env=self.env,capture_output=True,text=True,timeout=15)

    def test_unknown_inspect_and_failed_or_nonempty_listing_retain_live_consumer_data(self):
        local = self.context.create_scratch()
        report = self.root/'ci-artifacts/documents-r3'; report.mkdir()
        self.context.initialize_ledger(local,report,'cabinet-documents-77-12345-local')
        shutil.rmtree(local)  # completed LOCAL, exactly as in the reported composition.
        scratch = self.context.create_scratch()
        report = self.root/'ci-artifacts/documents-r3-s3'; report.mkdir()
        self.context.initialize_ledger(scratch,report,'cabinet-documents-77-12345-s3')
        (scratch/'bound-data').write_text('must survive')
        marker=f'-dir={scratch}/s3/data'
        child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)',marker])
        started=cleanup.identity(child.pid)
        try:
            for mode in ('error','present','rmerror','foreign'):
                self.mode.write_text(mode)
                self.calls.write_text('')
                result=self.shell()
                self.assertNotEqual(result.returncode,0)
                self.assertTrue(scratch.exists())
                self.assertEqual((scratch/'bound-data').read_text(),'must survive')
                self.assertIsNone(child.poll())
                self.assertEqual((self.foreign/'keep').read_text(),'foreign unchanged')
                if mode=='foreign': self.assertFalse(any(line.startswith('rm ') for line in self.calls.read_text().splitlines()))
            # Supervisor verifies and stops only its own stand-in container consumer.
            self.assertEqual(cleanup.identity(child.pid),started)
            self.assertIn(marker.encode(),Path(f'/proc/{child.pid}/cmdline').read_bytes().split(b'\0'))
            child.terminate(); child.wait(timeout=5)
            self.mode.write_text('absent')
            result=self.shell()
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertFalse(scratch.exists())
            self.assertEqual((self.foreign/'keep').read_text(),'foreign unchanged')
        finally:
            if child.poll() is None: child.terminate(); child.wait(timeout=5)

    def test_confirmed_exact_name_absence_and_no_resources_is_noop(self):
        result=self.shell()
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertFalse(self.context.parent.exists())
        self.assertEqual(self.calls.read_text().count('container ls -aq --filter name=^/'),4)
        self.assertEqual((self.foreign/'keep').read_text(),'foreign unchanged')


if __name__ == '__main__':
    unittest.main()
