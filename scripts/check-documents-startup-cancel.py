#!/usr/bin/env python3
"""Prove cancellation between native postmaster exec and PID-ledger publication."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[1]


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--pg-bin',required=True)
    parser.add_argument('--pg-share')
    parser.add_argument('--report-dir',required=True,type=Path)
    args=parser.parse_args()
    report=args.report_dir.resolve(); report.mkdir(parents=True,exist_ok=True)
    environment=os.environ.copy()
    environment.pop('DOCUMENTS_TEST_POSTGRES_IMAGE',None)
    environment.pop('DOCUMENTS_TEST_S3_IMAGE',None)
    command=[sys.executable,str(ROOT/'scripts/check-documents-runtime.py'),'--skip-build','--verify-startup-cancellation','--pg-bin',args.pg_bin,'--report-dir',str(report)]
    if args.pg_share: command+=['--pg-share',args.pg_share]
    with (report/'driver.log').open('wb') as output:
        result=subprocess.run(command,env=environment,stdout=output,stderr=subprocess.STDOUT,timeout=90)
    if result.returncode!=143: raise AssertionError('Expected caught SIGTERM cancellation')
    ledger=json.loads((report/'native-processes.json').read_text())
    for process in ledger['processes']:
        try:
            fields=Path(f"/proc/{process['pid']}/stat").read_text().rsplit(')',1)[1].split()
            if fields[0]!='Z' and fields[19]==process['start']: raise AssertionError('Owned process survived cancellation')
        except FileNotFoundError: pass
    if Path(ledger['scratch']).exists(): raise AssertionError('Stopped fixture directory was not removed')
    receipt={'sourceSha':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT).decode().strip(),'sourceTree':subprocess.check_output(['git','rev-parse','HEAD^{tree}'],cwd=ROOT).decode().strip(),'status':'PASSED','scenario':'SIGTERM immediately after PostgreSQL exec and before PID ledger publication','exitCode':result.returncode,'processesStopped':True,'ownedDirectoryRemovedAfterStop':True}
    (report/'cancellation-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')


if __name__=='__main__': main()
