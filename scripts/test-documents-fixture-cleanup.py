#!/usr/bin/env python3
"""Owned synthetic process checks for retain-on-uncertainty cleanup behavior."""
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import uuid

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('documents_cleanup',ROOT/'scripts/documents-fixture-cleanup.py')
cleanup=importlib.util.module_from_spec(spec);spec.loader.exec_module(cleanup)


class CleanupTest(unittest.TestCase):
    @patch.dict('os.environ', {}, clear=True)
    def check_uncertain(self,unreadable):
        scope='cabinet-documents-native-'+uuid.uuid4().hex
        source='0'*40
        scratch=Path(tempfile.mkdtemp(prefix='alpha-documents-runtime-',dir='/var/tmp'))
        (scratch/'fixture-scope.json').write_text(json.dumps({'scope':scope,'source':source}))
        marker='-Dcabinet.documents.fixture.scope='+scope
        process=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)',marker if unreadable else 'different-owned-test-marker'])
        ledger=scratch/'ledger.json'
        ledger.write_text(json.dumps({'scope':scope,'source':source,'scratch':str(scratch),'processes':[{'pid':process.pid,'start':cleanup.identity(process.pid),'marker':marker}]}))
        original=Path.read_bytes
        def guarded(path,*args,**kwargs):
            if path==Path(f'/proc/{process.pid}/cmdline'): raise PermissionError('Synthetic unreadable cmdline')
            return original(path,*args,**kwargs)
        try:
            if unreadable:
                with patch.object(Path,'read_bytes',guarded),self.assertRaises(RuntimeError): cleanup.cleanup(ledger,{'scope':scope,'source':source})
            else:
                with self.assertRaises(RuntimeError): cleanup.cleanup(ledger,{'scope':scope,'source':source})
            self.assertTrue(scratch.is_dir())
            self.assertIsNone(process.poll())
        finally:
            process.terminate();process.wait(timeout=5)
            shutil.rmtree(scratch)

    def test_marker_mismatch_retains_data_and_does_not_kill(self): self.check_uncertain(False)
    def test_unreadable_live_cmdline_retains_data_and_does_not_kill(self): self.check_uncertain(True)


if __name__=='__main__': unittest.main()
