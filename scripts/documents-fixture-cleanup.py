#!/usr/bin/env python3
"""Kill only recorded native fixture processes with matching Linux start time and scope."""
import argparse
import json
import os
from pathlib import Path
import signal
import shutil
import time


def identity(pid):
    try:
        return Path(f'/proc/{pid}/stat').read_text().rsplit(')',1)[1].split()[19]
    except (OSError, IndexError):
        return None


def cleanup(path, expected=None):
    if not path.exists(): return
    receipt=json.loads(path.read_text())
    if expected and any(receipt.get(key)!=value for key,value in expected.items()):
        raise ValueError('Fixture cleanup scope mismatch')
    scope=receipt['scope']
    for process in reversed(receipt['processes']):
        pid=int(process['pid'])
        if identity(pid)!=process['start']: continue
        try: arguments=Path(f'/proc/{pid}/cmdline').read_bytes().split(b'\0')
        except OSError: continue
        if process['marker'].encode() not in arguments: continue
        if process['marker'] != f'-Dcabinet.documents.fixture.scope={scope}' and not process['marker'].startswith('/tmp/alpha-documents-runtime-') and not process['marker'].startswith('/var/tmp/alpha-documents-runtime-') and not process['marker'].startswith('-dir=/tmp/alpha-documents-runtime-') and not process['marker'].startswith('-dir=/var/tmp/alpha-documents-runtime-'):
            raise ValueError('Unexpected fixture marker')
        os.kill(pid,signal.SIGTERM)
        deadline=time.monotonic()+5
        while identity(pid)==process['start'] and time.monotonic()<deadline: time.sleep(.1)
        if identity(pid)==process['start']: os.kill(pid,signal.SIGKILL)
    scratch=Path(receipt.get('scratch',''))
    if scratch.exists() and scratch.parent in (Path('/tmp'),Path('/var/tmp')) and scratch.name.startswith('alpha-documents-runtime-'):
        marker=scratch/'fixture-scope.json'
        if marker.is_file() and json.loads(marker.read_text())=={'scope':scope,'source':receipt['source']}:
            shutil.rmtree(scratch)


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('ledger',type=Path)
    parser.add_argument('--scope',required=True)
    parser.add_argument('--source',required=True)
    args=parser.parse_args()
    cleanup(args.ledger,{'scope':args.scope,'source':args.source})
