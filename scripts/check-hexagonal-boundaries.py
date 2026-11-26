#!/usr/bin/env python3
"""Check pure-core sources; Gradle checks their resolved production dependencies."""
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
MODULES = (
    "assessment-contracts", "assessment-engine", "catalog-model", "documents-model",
    "loan-math", "servicing-model", "servicing-core",
)
FORBIDDEN = re.compile(
    r"\b(?:org\.springframework|io\.grpc|org\.apache\.kafka|java\.sql|javax\.sql|"
    r"jakarta\.persistence|org\.hibernate|org\.flywaydb|org\.postgresql|"
    r"ru\.creditcabinet\.(?:\w+\.)*(?:infrastructure|web|http))\."
)


def without_comments(text):
    """Keep strings intact, including interpolation; strip nested Kotlin comments."""
    out, index, quote, depth = [], 0, None, 0
    while index < len(text):
        if depth:
            if text.startswith("/*", index):
                depth += 1
                index += 2
            elif text.startswith("*/", index):
                depth -= 1
                index += 2
            else:
                out.append("\n" if text[index] == "\n" else " ")
                index += 1
        elif quote:
            if text.startswith(quote, index):
                out.append(quote)
                index += len(quote)
                quote = None
            elif quote != '"""' and text[index] == "\\":
                out.append(text[index:index + 2])
                index += 2
            else:
                out.append(text[index])
                index += 1
        elif text.startswith("//", index):
            end = text.find("\n", index)
            index = len(text) if end == -1 else end
            out.append(" ")
        elif text.startswith("/*", index):
            depth, index = 1, index + 2
            out.append(" ")
        elif text.startswith('"""', index):
            quote = '"""'
            out.append(quote)
            index += 3
        elif text[index] in "\"'":
            quote = text[index]
            out.append(quote)
            index += 1
        else:
            out.append(text[index])
            index += 1
    return "".join(out)


def check(root=ROOT):
    errors, modules = [], {}
    for name in MODULES:
        module = root / "libs" / name
        sources = sorted((module / "src/main/kotlin").rglob("*.kt"))
        if not sources:
            errors.append(f"{name}: pure core has no Kotlin sources")
        for source in sources:
            # Keep string interpolation visible and normalize legal Kotlin qualification.
            text = re.sub(r"\s*\.\s*", ".", without_comments(source.read_text()).replace("`", ""))
            if FORBIDDEN.search(text):
                errors.append(f"{source.relative_to(root)}: infrastructure reference in pure core")
        modules[name] = len(sources)
    return {"modules": modules, "errors": errors, "scope": "source; resolved dependencies checked by checkHexagonalDependencies"}


if __name__ == "__main__":
    report = check()
    print(json.dumps(report, sort_keys=True))
    raise SystemExit(bool(report["errors"]))
