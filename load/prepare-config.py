#!/usr/bin/env python3
"""Add upstream addresses to the isolated reliability gateway log."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
from gateway_config import gateway_source  # noqa: E402

source = gateway_source(ROOT)
assert source.count("server {") == 1
prefix = "log_format cabinet_load '$http_x_request_id upstream=$upstream_addr status=$status method=$request_method uri=$uri';\n"
config = prefix + source.replace("server {", "server {\n    access_log /var/log/nginx/access.log cabinet_load;", 1)
directory = ROOT / "load/reports"
directory.mkdir(exist_ok=True)
(directory / "gateway.conf").write_text(config, encoding="utf-8", newline="\n")
print("load/reports/gateway.conf: журнал upstream без токенов и тел запросов")
