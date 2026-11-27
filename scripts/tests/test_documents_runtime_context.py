import importlib.util
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

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from documents_runtime_context import RuntimeContext


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

    def test_initial_journal_precedes_s3_constructor_and_survives_hard_kill(self):
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
            self.assertEqual(cleaned.returncode, 0, cleaned.stderr)
            self.assertFalse(scratch.exists())
        finally:
            if scratch and scratch.exists(): shutil.rmtree(scratch)
            if context.parent.exists():
                # Only this synthetic test job; also remove a failed pre-journal candidate.
                shutil.rmtree(context.parent)

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
            if command[1] == 'port':
                return b'127.0.0.1:12345'
            for index, argument in enumerate(command):
                if argument == '--mount':
                    mount = command[index + 1]
                    source = Path(mount.split('source=', 1)[1].split(',', 1)[0])
                    self.assertTrue(source.exists())
                    self.assertTrue(source.is_relative_to(scratch))
                    seen.append(source)
            return b'a' * 64
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
        with patch.object(s3.subprocess, 'check_output', docker), patch.object(s3.S3Fixture, 'request', request), patch.object(s3.HTTP, 'open', side_effect=denied):
            fixture = s3.S3Fixture(scratch, self.root, lambda: 12345, image=s3.PINNED_IMAGE,
                                   docker_env={}, scope='cabinet-documents-77-12345-s3', labels={})
        self.assertEqual(len(seen), 4)
        self.assertIn('PUT-HEAD-GET-size-sha256-both-buckets', fixture.provenance['strictChecks'])
        self.assertEqual(len(objects), 2)

    def test_docker_mounts_exist_under_shared_job_before_strict_readiness(self):
        self.check_s3_readiness()

    def test_shared_scratch_does_not_weaken_readiness_checksum(self):
        with self.assertRaisesRegex(AssertionError, 'checksum or size mismatch'):
            self.check_s3_readiness(corrupt=True)


if __name__ == '__main__':
    unittest.main()
