"""Shared Documents scratch identity, reconstructed by a fresh CI cleanup shell."""
import os
import json
from pathlib import Path
import re
import tempfile
import socket
import subprocess


def start_ci_container(command, environment, *, name, image, labels, container_port):
    """Retry only a verified owned stopped fixture after host publication collision."""
    required = ('cabinet.ci.job', 'cabinet.ci.project', 'cabinet.source', 'cabinet.profile', 'cabinet.resource')
    if (any(key not in labels for key in required) or labels.get('cabinet.fixture') != 'documents-r3'
            or not labels['cabinet.ci.job'].isdigit() or not labels['cabinet.ci.project'].isdigit()
            or not re.fullmatch('[a-f0-9]{40}', labels['cabinet.source'])
            or labels['cabinet.profile'] not in ('local', 's3') or labels['cabinet.resource'] not in ('postgres', 's3')
            or name != f"cabinet-documents-{labels['cabinet.ci.project']}-{labels['cabinet.ci.job']}-{labels['cabinet.profile']}-{labels['cabinet.resource']}"):
        raise ValueError('Container retry requires exact Documents CI identity')
    publication = f'127.0.0.1::{container_port}'
    if command[:2] != ['docker', 'run'] or command.count(publication) != 1:
        raise ValueError('Expected one loopback fixture publication')
    def docker(arguments):
        try:
            return subprocess.run(arguments, env=environment, capture_output=True, text=True, timeout=45)
        except subprocess.TimeoutExpired:
            raise RuntimeError('Fixture Docker operation timed out; retained') from None
    used_ports = set()
    for attempt in range(3):
        for _ in range(10):
            with socket.socket() as endpoint:
                endpoint.bind(('127.0.0.1', 0))
                host_port = endpoint.getsockname()[1]
            if host_port not in used_ports: break
        else:
            raise RuntimeError('Cannot select a fresh fixture host port')
        used_ports.add(host_port)
        # Keep a failed start inspectable until its identity has been verified.
        current = [f'127.0.0.1:{host_port}:{container_port}' if value == publication else value
                   for value in command if value != '--rm']
        started = docker(current)
        if started.returncode == 0:
            identity = started.stdout.strip()
            if not re.fullmatch('[a-f0-9]{64}', identity):
                raise RuntimeError('Fixture returned an invalid container identity; retained')
            return identity, host_port
        failure = started.stderr.lower()
        collision = (f'127.0.0.1:{host_port}' in failure and 'bind' in failure
                     and ('address already in use' in failure or 'port is already allocated' in failure))
        if not collision or attempt == 2:
            raise RuntimeError('Fixture container start failed; retained (port collision)' if collision
                               else 'Fixture container start failed; retained (non-publication error)')
        template = '{"id":{{json .Id}},"name":{{json .Name}},"image":{{json .Config.Image}},"running":{{json .State.Running}},"labels":{{json .Config.Labels}}}'
        inspected = docker(['docker', 'inspect', '--format', template, name])
        if inspected.returncode:
            raise RuntimeError('Cannot verify failed fixture identity; retained')
        try:
            metadata = json.loads(inspected.stdout)
            owned = (re.fullmatch('[a-f0-9]{64}', metadata['id']) and metadata['name'] == '/' + name
                     and metadata['image'] == image and metadata['running'] is False
                     and all(metadata['labels'].get(key) == value for key, value in labels.items()))
        except (ValueError, KeyError, TypeError, AttributeError):
            owned = False
        if not owned:
            raise RuntimeError('Failed fixture ownership/state mismatch; retained')
        removed = docker(['docker', 'rm', metadata['id']])
        if removed.returncode:
            raise RuntimeError('Failed fixture cleanup could not be confirmed; retained')


class RuntimeContext:
    def __init__(self, root, source, environment=None):
        self.root = Path(root).resolve()
        self.source = source
        self.environment = os.environ if environment is None else environment
        self.job = self.environment.get('CI_JOB_ID', '')
        self.project = self.environment.get('CI_PROJECT_ID', '')
        self.ci = any(key in self.environment for key in ('GITLAB_CI', 'CI_JOB_ID', 'CI_PROJECT_DIR', 'CI_PROJECT_ID'))
        if self.ci:
            project_dir = Path(self.environment.get('CI_PROJECT_DIR', ''))
            if (not self.job.isdigit() or not self.project.isdigit() or not project_dir.is_absolute()
                    or project_dir != self.root or project_dir.resolve() != project_dir
                    or not re.fullmatch('[a-f0-9]{40}', source)
                    or self.environment.get('CI_COMMIT_SHA') != source):
                raise ValueError('Documents scratch requires exact CI project/job/source context')
            # Derive this again in after_script: RUNNER_TEMP/TMPDIR exports do not persist.
            self.parent = self.root / 'ci-artifacts' / '.job' / f'documents-{self.project}-{self.job}'
            self._check_shared_path()
        else:
            self.parent = Path(tempfile.gettempdir()).resolve()
            if not (self.parent.is_relative_to('/tmp') or self.parent.is_relative_to('/var/tmp')):
                self.parent = Path('/tmp')

    def _check_shared_path(self):
        if self.parent.resolve() != self.parent:
            raise ValueError('Documents CI scratch must not traverse symlinks')

    def create_scratch(self):
        if self.ci:
            self._check_shared_path()
        self.parent.mkdir(parents=True, exist_ok=True)
        return Path(tempfile.mkdtemp(prefix='alpha-documents-runtime-', dir=self.parent))

    def initialize_ledger(self, scratch, report, scope, *, native_s3=False):
        if not self.permits(scratch, scope):
            raise ValueError('Initial fixture journal is outside the runtime context')
        ledger = {'scope': scope, 'source': self.source, 'scratch': str(scratch), 'processes': []}
        if native_s3:
            ledger['nativeS3'] = {'marker': f'-dir={scratch}/s3/data'}
        # Publish both ownership and the empty journal before any fixture side effect.
        for target, value in ((scratch / 'fixture-scope.json', {'scope': scope, 'source': self.source}),
                              (report / 'native-processes.json', ledger)):
            temporary = target.with_suffix('.new')
            with temporary.open('w') as output:
                output.write(json.dumps(value) + '\n')
                output.flush()
                os.fsync(output.fileno())
            temporary.replace(target)
            descriptor = os.open(target.parent, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
        return ledger

    def permits(self, scratch, scope):
        scratch = Path(scratch)
        if scratch.resolve() != scratch.absolute() or scratch.is_symlink() or not scratch.name.startswith('alpha-documents-runtime-'):
            return False
        if self.ci:
            self._check_shared_path()
            return (scratch.parent == self.parent
                    and scope in (f'cabinet-documents-{self.project}-{self.job}-local', f'cabinet-documents-{self.project}-{self.job}-s3'))
        return scratch.is_relative_to('/tmp') or scratch.is_relative_to('/var/tmp')
