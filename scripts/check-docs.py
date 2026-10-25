#!/usr/bin/env python3
"""Check local documentation links and the required decision/diagram structure."""
from pathlib import Path
import re
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parent.parent
files = [ROOT / "README.md", *sorted((ROOT / "docs").rglob("*.md")), ROOT / "contracts/README.md", ROOT / "e2e/README.md"]
files.extend(file for folder in ["security", "load", "deploy", "monitoring"] for file in sorted((ROOT / folder).glob("*.md")))
errors = []
for file in files:
    text = file.read_text(encoding="utf-8")
    fences = re.findall(r"^```.*$", text, re.MULTILINE)
    if len(fences) % 2:
        errors.append(f"{file.relative_to(ROOT)}: незакрытый блок кода")
    prose = re.sub(r"```.*?```", "", text, flags=re.DOTALL)
    for target in re.findall(r"\[[^\]]*\]\(([^)]+)\)", prose):
        target = target.split("#", 1)[0].strip("<>")
        if not target or re.match(r"[a-zA-Z][a-zA-Z\d+.-]*:", target):
            continue
        if not (file.parent / unquote(target)).exists():
            errors.append(f"{file.relative_to(ROOT)}: нет файла {target}")

adrs = sorted((ROOT / "docs/adr").glob("*.md"))
# Decisions only accumulate: a floor, not a ceiling.
if len(adrs) < 8:
    errors.append("docs/adr: требуется не меньше 8 решений")
for file in adrs:
    text = file.read_text(encoding="utf-8")
    for required in ["Контекст.", "Решение.", "Альтернативы.", "Последствия."]:
        if required not in text:
            errors.append(f"{file.name}: отсутствует {required}")

architecture = (ROOT / "docs/architecture.md").read_text(encoding="utf-8")
if architecture.count("```mermaid") < 4 or architecture.count("sequenceDiagram") < 3:
    errors.append("architecture.md: нужны контейнеры и минимум три последовательности Mermaid")
if errors:
    raise SystemExit("\n".join(errors))
print(f"Проверены ссылки и блоки в {len(files)} документах, {len(adrs)} ADR.")
