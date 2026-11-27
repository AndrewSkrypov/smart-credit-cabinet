#!/usr/bin/env python3
"""Fetch the official, SHA-pinned SeaweedFS 4.55 native fixture without Docker."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import tarfile
import tempfile
from urllib.request import urlopen

URL='https://github.com/seaweedfs/seaweedfs/releases/download/4.55/linux_amd64.tar.gz'
ARCHIVE_SHA256='f156c609eeaf9e5fcfeea33fd70411222005f83760aa95893c9d58eff006b45c'
MAX_ARCHIVE=64*1024*1024


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--output-dir',type=Path,required=True)
    args=parser.parse_args()
    destination=args.output_dir.resolve()
    destination.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='documents-s3-download-',dir=destination) as directory:
        archive=Path(directory)/'fixture.tar.gz'
        with urlopen(URL,timeout=30) as response,archive.open('wb') as output:
            total=0
            while chunk:=response.read(1024*1024):
                total+=len(chunk)
                if total>MAX_ARCHIVE: raise ValueError('Native S3 archive exceeds limit')
                output.write(chunk)
        if hashlib.sha256(archive.read_bytes()).hexdigest()!=ARCHIVE_SHA256:
            raise ValueError('Pinned native S3 archive checksum mismatch')
        binary=destination/'weed'
        with tarfile.open(archive,'r:gz') as package:
            candidates=[item for item in package.getmembers() if item.name in ('weed','./weed') and item.isfile()]
            if len(candidates)!=1 or candidates[0].size>256*1024*1024: raise ValueError('Unexpected native S3 archive members')
            with package.extractfile(candidates[0]) as source,binary.open('wb') as output:
                shutil.copyfileobj(source,output,length=1024*1024)
        binary.chmod(0o700)
        manifest={'version':'4.55','archiveUrl':URL,'archiveSha256':ARCHIVE_SHA256,'binary':str(binary),'binarySha256':hashlib.sha256(binary.read_bytes()).hexdigest()}
        (destination/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
        print(json.dumps(manifest))


if __name__=='__main__': main()
