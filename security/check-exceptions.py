#!/usr/bin/env python3
"""Fail accepted findings when their version, deadline or feature assumptions change."""
from datetime import datetime, timezone, date
from hashlib import sha256
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent.parent
policy = json.loads((ROOT / "security/accepted-vulnerabilities.json").read_text(encoding="utf-8"))
ignored = {line.strip() for line in (ROOT / ".trivyignore").read_text(encoding="utf-8").splitlines() if line.strip() and not line.startswith("#")}
assert ignored == {item["id"] for item in policy["exceptions"]}, "Исключения Trivy должны иметь явное обоснование и источник"
assert datetime.now(timezone.utc).date() <= date.fromisoformat(policy["review_by"]), "Истёк срок пересмотра принятых уязвимостей"
version_pattern = re.compile(r"^org\.springframework:spring-webmvc:([^=]+)=", re.MULTILINE)
for file in [*ROOT.glob("services/*/gradle.lockfile"), ROOT / "libs/platform-common/gradle.lockfile"]:
    versions = version_pattern.findall(file.read_text(encoding="utf-8"))
    assert versions and all(version in policy["reviewed_versions"] for version in versions), f"Повторно оцените исключения для версии в {file.relative_to(ROOT)}"
forbidden = re.compile(r"XsltView|XsltViewResolver|SseEmitter|ResponseBodyEmitter|ServerSentEvent|FragmentsRendering|RenderingResponse")
stream_type = re.compile(r"TEXT_EVENT_STREAM|text/event-stream")
files = [file for folder in ["services", "libs"] for file in ROOT.glob(f"{folder}/*/src/main/**/*")
         if file.is_file() and file.suffix in {".kt", ".xml", ".yml", ".yaml", ".properties"}]
violations = []
servlet_streams = {}
for file in files:
    source = file.read_text(encoding="utf-8")
    name = file.relative_to(ROOT).as_posix()
    if forbidden.search(source):
        violations.append(name)
    if stream_type.search(source):
        servlet_streams[name] = sha256(source.encode("utf-8")).hexdigest()
assert not violations, "Изменились предпосылки исключений; нужен пересмотр: " + ", ".join(violations)
assert servlet_streams == policy["reviewed_servlet_streams"], "Изменились Servlet SSE-маршруты; проверьте отсутствие рендеринга фрагментов"
print(f"Проверены {len(files)} файлов, две предпосылки и версии Spring MVC; пересмотр до {policy['review_by']}.")
