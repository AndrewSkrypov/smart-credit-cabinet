#!/usr/bin/env python3
"""Stop only recorded fixture identities; never remove data while a process may use it."""
import argparse
import json
import os
from pathlib import Path
import signal
import shutil
import time


def identity(pid):
    try:
        fields=Path(f'/proc/{pid}/stat').read_text().rsplit(')',1)[1].split()
        return None if fields[0]=='Z' else fields[19]
    except (FileNotFoundError, ProcessLookupError):
        return None
    except (OSError, IndexError) as failure:
        raise RuntimeError("Cannot confirm owned process state; data retained") from failure


def cleanup(path, expected=None):
    if not path.exists(): return
    receipt=json.loads(path.read_text())
    if expected and any(receipt.get(key)!=value for key,value in expected.items()):
        raise ValueError('Fixture cleanup scope mismatch')
    scope=receipt['scope']
    scratch=Path(receipt.get('scratch',''))
    resolved=scratch.resolve()
    valid_scratch=(resolved.is_relative_to('/tmp') or resolved.is_relative_to('/var/tmp')) and resolved==scratch.absolute() and not scratch.is_symlink() and scratch.name.startswith('alpha-documents-runtime-')
    marker=scratch/'fixture-scope.json'
    if scratch.exists() and (not valid_scratch or not marker.is_file() or json.loads(marker.read_text())!={'scope':scope,'source':receipt['source']}):
        raise ValueError('Fixture directory ownership mismatch')
    processes=list(receipt['processes'])
    native=receipt.get('nativePg')
    if native and scratch.exists():
        directory=scratch/'db'
        if native['directory']!=str(directory): raise ValueError('Native PostgreSQL directory mismatch')
        if not any(item['marker']==str(directory) for item in processes):
            # Planned identity is written atomically before exec. A hard kill between exec
            # and PID publication may leave only the postmaster's own pid file.
            deadline=time.monotonic()+3
            while not (directory/'postmaster.pid').exists() and time.monotonic()<deadline: time.sleep(.1)
            if not (directory/'postmaster.pid').exists():
                raise RuntimeError('PostgreSQL startup state unresolved; owned data retained')
            pid=int((directory/'postmaster.pid').read_text().splitlines()[0])
            started=identity(pid)
            if started is not None:
                processes.insert(0,{'pid':pid,'start':started,'marker':str(directory)})
    for process in reversed(processes):
        pid=int(process['pid'])
        if identity(pid)!=process['start']: continue
        try: arguments=Path(f'/proc/{pid}/cmdline').read_bytes().split(b'\0')
        except OSError as failure:
            if identity(pid)==process['start']: raise RuntimeError('Cannot inspect live owned process; data retained') from failure
            continue
        if process['marker'].encode() not in arguments:
            if identity(pid)==process['start']: raise RuntimeError('Live recorded identity has an unconfirmed marker; data retained')
            continue
        is_pg=process['marker']==str(scratch/'db')
        valid_marker=process['marker']==f'-Dcabinet.documents.fixture.scope={scope}' or is_pg or process['marker']==f'-dir={scratch}/s3/data'
        if not valid_marker: raise ValueError('Unexpected fixture marker')
        if identity(pid)!=process['start']: continue
        os.kill(pid,signal.SIGINT if is_pg else signal.SIGTERM)
        deadline=time.monotonic()+10
        while identity(pid)==process['start'] and time.monotonic()<deadline: time.sleep(.1)
        if identity(pid)==process['start']:
            os.kill(pid,signal.SIGKILL)
            deadline=time.monotonic()+2
            while identity(pid)==process['start'] and time.monotonic()<deadline: time.sleep(.1)
        if identity(pid)==process['start']:
            raise RuntimeError('Owned fixture process remains alive; data retained')
    if scratch.exists() and valid_scratch:
        shutil.rmtree(scratch)


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('ledger',type=Path)
    parser.add_argument('--scope',required=True)
    parser.add_argument('--source',required=True)
    args=parser.parse_args()
    cleanup(args.ledger,{'scope':args.scope,'source':args.source})
