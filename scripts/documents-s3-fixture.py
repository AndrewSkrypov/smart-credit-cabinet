"""Private SeaweedFS 4.55 fixture. Native binary provenance is mandatory outside CI."""
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import time
from datetime import datetime, timezone
from urllib.error import HTTPError
from urllib.request import Request, build_opener, ProxyHandler

PINNED_IMAGE = 'chrislusf/seaweedfs:4.55@sha256:7ca46aeae2f223b37e5d91593c17ab37738eec6df6ab69b48a0487f7643342b9'
HTTP = build_opener(ProxyHandler({}))

class S3Fixture:
    def __init__(self, scratch, report, free_port, *, binary=None, binary_sha256=None, image=None, docker_env=None, scope=None, labels=None, environment=None, register=None):
        self.scratch = Path(scratch) / 's3'
        self.scratch.mkdir()
        self.report = report
        self.docker_env=docker_env
        self.environment=environment or {}
        self.container = None
        self.process = None
        self.handle = None
        self.identities = {name: (secrets.token_hex(16), secrets.token_hex(24)) for name in ('source', 'target')}
        self.buckets = {name: f'documents-{name}-{secrets.token_hex(6)}' for name in self.identities}
        self.port = free_port()
        self.endpoint = f'http://127.0.0.1:{self.port}'
        config = {'identities': [{'name': name, 'credentials': [{'accessKey': key, 'secretKey': secret}], 'actions': [f'{action}:{self.buckets[name]}' + ('/documents/*' if action in ('Read', 'Write') else '') for action in ('Read', 'Write', 'List')]} for name, (key, secret) in self.identities.items()]}
        configuration = self.scratch / 'iam.json'
        configuration.write_text(json.dumps(config))
        configuration.chmod(0o600)
        data = self.scratch / 'data'; data.mkdir()
        # A native process must not discover a host/user filer.toml with external storage.
        (self.scratch/'filer.toml').write_text('[leveldb2]\nenabled = true\ndir = '+json.dumps(str(data/'filerldb2'))+'\n')
        common = ['mini', '-ip=127.0.0.1', '-admin.ui=false', '-webdav=false', '-s3.iam=false', '-s3.port.iceberg=0', '-s3.port.lance=0', '-s3.autoCreateBucket=false', '-s3.allowDeleteBucketNotEmpty=false', '-s3.concurrentUploadLimitMB=16', '-s3.readerCacheSizeMB=16', '-master.telemetry=false', '-master.volumeSizeLimitMB=64', '-volume.max=4', '-bucket=' + ','.join(self.buckets.values())]
        if image:
            if image != PINNED_IMAGE or docker_env is None or scope is None or labels is None:
                raise ValueError('Docker S3 fixture is limited to the labeled cabinet-ci runner and pinned image')
            self.container = subprocess.check_output(['docker','run','-d','--rm',*[part for key,value in (labels|{'cabinet.resource':'s3'}).items() for part in ('--label',f'{key}={value}')],'--name',f'{scope}-s3','--memory','384m','-p','127.0.0.1::8333','--mount',f'type=bind,source={configuration},target=/fixture/iam.json,readonly','--mount',f'type=bind,source={data},target=/data','-e','GOMEMLIMIT=256MiB','--entrypoint','weed',image,*common,'-ip.bind=0.0.0.0','-dir=/data','-s3.config=/fixture/iam.json'],env=docker_env).decode().strip()
            address = subprocess.check_output(['docker','port',self.container,'8333/tcp'],env=docker_env).decode().strip()
            self.port = int(address.rsplit(':',1)[1]); self.endpoint = f'http://127.0.0.1:{self.port}'
            self.provenance = {'image':image}
        else:
            if not binary or not binary_sha256 or not re.fullmatch('[a-f0-9]{64}',binary_sha256):
                raise ValueError('S3 gate requires native 4.55 binary with explicit SHA-256 or the pinned CI image; it never skips')
            binary = Path(binary).resolve()
            actual = hashlib.sha256(binary.read_bytes()).hexdigest()
            if actual != binary_sha256: raise ValueError('Native S3 binary checksum mismatch')
            version = subprocess.check_output([str(binary),'version'],env=self.environment).decode()
            if not re.search(r'\b4\.55\b',version): raise ValueError('Native S3 fixture must be SeaweedFS 4.55')
            self.provenance = {'binarySha256':actual,'version':version.strip()}
            self.handle = (report/'s3-server.log').open('ab')
            self.process = subprocess.Popen([str(binary),*common,'-ip.bind=127.0.0.1',f'-dir={data}',f'-s3.config={configuration}',f'-s3.port={self.port}',f'-master.port={free_port()}',f'-master.port.grpc={free_port()}',f'-volume.port={free_port()}',f'-volume.port.grpc={free_port()}',f'-filer.port={free_port()}',f'-filer.port.grpc={free_port()}',f'-grpc.localSocketDir={self.scratch}'],cwd=self.scratch,env=self.environment|{'GOMEMLIMIT':'256MiB'},stdout=self.handle,stderr=subprocess.STDOUT)
        if self.process and register: register(self.process,f"-dir={data}")
        deadline = time.monotonic()+60
        last_failure='not-listening'
        while True:
            try:
                self.request('source','HEAD','')
                break
            except HTTPError as failure:
                # Persist only a bounded protocol code, never credentials or response messages.
                import xml.etree.ElementTree as ET
                try: code=ET.fromstring(failure.read(4096)).findtext('Code') or 'unknown'
                except ET.ParseError: code='non-xml'
                failure.close()
                last_failure=f'HTTP-{failure.code}-'+(code if re.fullmatch('[A-Za-z0-9_-]{1,64}',code) else 'unknown')
            except OSError:
                last_failure='not-listening'
            if self.process and self.process.poll() is not None: raise RuntimeError('Private S3 process exited before readiness')
            if time.monotonic()>deadline: raise RuntimeError('Private S3 readiness deadline exceeded: '+last_failure)
            time.sleep(.3)

    def request(self, identity, method, name, body=b''):
        access,secret = self.identities[identity]
        uri = '/' + self.buckets[identity] + ('/documents/'+name if name else '')
        now = datetime.now(timezone.utc)
        stamp = now.strftime('%Y%m%dT%H%M%SZ'); date = now.strftime('%Y%m%d')
        authority = f'127.0.0.1:{self.port}'
        checksum = hashlib.sha256(body).hexdigest()
        signed_headers = 'host;x-amz-content-sha256;x-amz-date'
        canonical = '\n'.join((method,uri,'',f'host:{authority}\nx-amz-content-sha256:{checksum}\nx-amz-date:{stamp}\n',signed_headers,checksum))
        scope = f'{date}/us-east-1/s3/aws4_request'
        string = '\n'.join(('AWS4-HMAC-SHA256',stamp,scope,hashlib.sha256(canonical.encode()).hexdigest()))
        def sign(key,value): return hmac.new(key,value.encode(),hashlib.sha256).digest()
        key = sign(sign(sign(sign(('AWS4'+secret).encode(),date),'us-east-1'),'s3'),'aws4_request')
        signature = hmac.new(key,string.encode(),hashlib.sha256).hexdigest()
        headers = {'Host':authority,'x-amz-content-sha256':checksum,'x-amz-date':stamp,'Authorization':f'AWS4-HMAC-SHA256 Credential={access}/{scope}, SignedHeaders={signed_headers}, Signature={signature}'}
        with HTTP.open(Request(self.endpoint+uri,body if method=='PUT' else None,headers,method=method),timeout=10) as response:
            return dict(response.headers), response.read(21*1024*1024)

    def verify(self, identity, name, size, checksum):
        head,_ = self.request(identity,'HEAD',name)
        _,body = self.request(identity,'GET',name)
        normalized={key.lower():value for key,value in head.items()}
        if int(normalized['content-length']) != size or len(body) != size or hashlib.sha256(body).hexdigest()!=checksum:
            raise AssertionError('S3 HEAD/GET checksum or size mismatch')

    def configuration(self, identity):
        key,secret = self.identities[identity]
        return {'endpoint':self.endpoint,'region':'us-east-1','bucket':self.buckets[identity],'access-key':key,'secret-key':secret}

    def close(self):
        if getattr(self, "process", None) and self.process.poll() is None:
            self.process.terminate()
            try: self.process.wait(timeout=15)
            except subprocess.TimeoutExpired: self.process.kill(); self.process.wait(timeout=5)
        if getattr(self, "handle", None): self.handle.close()
        if getattr(self, "container", None):
            with (self.report/'s3-server.log').open('ab') as output: subprocess.run(['docker','logs',self.container],env=self.docker_env,stdout=output,stderr=subprocess.STDOUT)
            subprocess.run(['docker','rm','-fv',self.container],env=self.docker_env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
