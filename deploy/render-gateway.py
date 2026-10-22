#!/usr/bin/env python3
"""Derive ingress settings while trusting forwarded headers only from the private Caddy address."""
import argparse
import ipaddress
from pathlib import Path

parser = argparse.ArgumentParser(description="Подготовить конфигурацию gateway для Caddy")
parser.add_argument("--caddy-ip", default="172.30.40.2")
parser.add_argument("--output")
args = parser.parse_args()
address = str(ipaddress.IPv4Address(args.caddy_ip))
root = Path(__file__).resolve().parent.parent
text = (root / "docker/gateway/nginx.conf").read_text(encoding="utf-8")
assert text.count("proxy_set_header X-Forwarded-Proto $scheme;") == 1
text = text.replace("proxy_set_header X-Forwarded-Proto $scheme;", "proxy_set_header X-Forwarded-Proto $cabinet_forwarded_proto;")
text = text.replace("proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;", "proxy_set_header X-Forwarded-For $remote_addr;")
prefix = f"""set_real_ip_from {address};
real_ip_header X-Forwarded-For;
real_ip_recursive on;
map $realip_remote_addr $cabinet_forwarded_proto {{
    {address} $http_x_forwarded_proto;
    default $scheme;
}}
log_format cabinet_prod '$remote_addr peer=$realip_remote_addr proto=$cabinet_forwarded_proto id=$sent_http_x_request_id $request_method $uri $status';
access_log /dev/stdout cabinet_prod;

"""
target = Path(args.output).resolve() if args.output else root / "deploy/runtime/gateway.conf"
assert target.is_relative_to(root / "deploy/runtime"), "Конфигурация должна оставаться в deploy/runtime"
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(prefix + text, encoding="utf-8")
print("Конфигурация gateway подготовлена; доверен только внутренний адрес Caddy")
