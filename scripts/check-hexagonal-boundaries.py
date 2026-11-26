#!/usr/bin/env python3
"""Check source and declared production dependencies of the extracted pure cores."""
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
MODULES = (
    "assessment-contracts", "assessment-engine", "catalog-model", "documents-model",
    "loan-math", "servicing-model", "servicing-core",
)
ALLOWED_PROJECTS = {":libs:" + name for name in MODULES}
FORBIDDEN = re.compile(
    r"\b(?:org\.springframework|io\.grpc|org\.apache\.kafka|java\.sql|javax\.sql|"
    r"jakarta\.persistence|org\.hibernate|org\.flywaydb|org\.postgresql|"
    r"ru\.creditcabinet\.(?:\w+\.)*(?:infrastructure|web|http))\."
)


def check(root=ROOT):
    errors, modules = [], {}
    for name in MODULES:
        module = root / "libs" / name
        sources = sorted((module / "src/main/kotlin").rglob("*.kt"))
        if not sources:
            errors.append(f"{name}: pure core has no Kotlin sources")
        for source in sources:
            # Ignore comments; qualified references are checked as well as imports.
            text = re.sub(r"/\*.*?\*/|//[^\n]*", "", source.read_text(), flags=re.S)
            if FORBIDDEN.search(text):
                errors.append(f"{source.relative_to(root)}: infrastructure reference in pure core")
        build = re.sub(r"/\*.*?\*/|//[^\n]*", "", (module / "build.gradle.kts").read_text(), flags=re.S)
        dependencies = re.finditer(
            r'\b(?:api|implementation|compileOnly|runtimeOnly)\s*\(\s*'
            r'(project\s*\(\s*"[^"]+"\s*\)|"[^"]+"|[^)]*)\s*\)', build,
        )
        for dependency in dependencies:
            value = dependency[1].strip()
            project = re.fullmatch(r'project\s*\(\s*"([^"]+)"\s*\)', value)
            if project and project[1] in ALLOWED_PROJECTS:
                continue
            if re.fullmatch(r'"org\.jetbrains\.kotlin:[^"]+"', value):
                continue
            errors.append(f"libs/{name}/build.gradle.kts: unapproved production dependency")
        modules[name] = len(sources)
    return {"modules": modules, "errors": errors, "scope": "source and declared production dependencies"}


if __name__ == "__main__":
    report = check()
    print(json.dumps(report, sort_keys=True))
    raise SystemExit(bool(report["errors"]))
