"""Shared Documents scratch identity, reconstructed by a fresh CI cleanup shell."""
import os
import json
from pathlib import Path
import re
import tempfile


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

    def initialize_ledger(self, scratch, report, scope):
        if not self.permits(scratch, scope):
            raise ValueError('Initial fixture journal is outside the runtime context')
        ledger = {'scope': scope, 'source': self.source, 'scratch': str(scratch), 'processes': []}
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
