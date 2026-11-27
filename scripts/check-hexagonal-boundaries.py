#!/usr/bin/env python3
"""Check inward dependencies in Kotlin sources and compiled production classes."""
import argparse
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
MODULES = (
    "assessment-contracts", "assessment-engine", "catalog-model", "documents-model",
    "loan-math", "servicing-model", "servicing-core",
)
LEGACY_SERVICES = ("credit", "engagement", "identity")
ADAPTERS = {"adapter", "adapters", "config", "controller", "dto", "grpc", "http", "infrastructure", "persistence", "web"}
DOMAIN_FRAMEWORK = ("org.springframework", "jakarta", "org.slf4j", "io.micrometer", "io.opentelemetry")
APPLICATION_METADATA = (
    "org.springframework.stereotype.Component", "org.springframework.stereotype.Service",
    "org.springframework.transaction.annotation.Isolation", "org.springframework.transaction.annotation.Propagation",
    "org.springframework.transaction.annotation.Transactional",
)
TECHNOLOGY = (
    "com.fasterxml.jackson", "com.google.protobuf", "io.grpc", "org.apache.kafka", "java.sql", "javax.sql",
    "jakarta.persistence", "javax.persistence", "jakarta.servlet", "javax.servlet", "jakarta.validation",
    "java.net.http", "org.hibernate", "org.flywaydb", "org.postgresql", "org.springframework.http",
    "org.springframework.web", "org.springframework.jdbc", "org.springframework.dao", "org.springframework.data",
    "org.springframework.kafka", "org.springframework.transaction.support", "org.springframework.security",
    "io.swagger", "io.jsonwebtoken", "io.lettuce", "redis.clients", "okhttp3", "org.apache.pdfbox", "org.apache.poi",
)
DATABASE = ("java.sql", "javax.sql", "jakarta.persistence", "javax.persistence", "org.hibernate", "org.postgresql",
            "org.springframework.jdbc", "org.springframework.data.jpa")
SHARED_ADAPTERS = (
    "ru.creditcabinet.common.outbox.DurableOutbox", "ru.creditcabinet.common.limits.SharedLimits",
    "ru.creditcabinet.common.limits.ValkeyState", "ru.creditcabinet.common.security.CurrentCaller",
    "ru.creditcabinet.common.security.JwtVerifier", "ru.creditcabinet.common.security.RevocationWatermarks",
    "ru.creditcabinet.common.mail.MailCapabilities", "ru.creditcabinet.common.exception.ApiException",
)
PACKAGE = re.compile(r"(?m)^\s*package\s+([\w.]+)\s*$")
QUALIFIED = re.compile(r"\b(?:[a-zA-Z_]\w*\.)+(?:[a-zA-Z_]\w*|\*)\b")
JDEPS_EDGE = re.compile(r"^\s+(\S+)\s+->\s+(\S+)\s+\S")


def matches_prefix(reference, prefixes):
    return any(reference == prefix or reference.startswith((prefix + ".", prefix + "$")) for prefix in prefixes)


def layer(name):
    parts = name.split(".")
    if parts[:2] != ["ru", "creditcabinet"]:
        return None
    # The first segment after the root is the context, even when it is named "application".
    for part in reversed(parts[3:]):
        if part == "domain":
            return "domain"
        if part in {"application", "service"}:
            return "application"
        if part in {"controller", "web", "http"}:
            return "transport"
        if part in ADAPTERS:
            return "adapter"
    return None


def forbidden_reference(source_layer, reference):
    target_layer = layer(reference)
    if source_layer in {"pure", "domain"}:
        return (target_layer in {"application", "adapter", "transport"}
                or matches_prefix(reference, (*DOMAIN_FRAMEWORK, *TECHNOLOGY, *SHARED_ADAPTERS,
                                              "ru.creditcabinet.common.exception")))
    if source_layer == "application":
        return (target_layer in {"adapter", "transport"}
                or (matches_prefix(reference, DOMAIN_FRAMEWORK) and not matches_prefix(reference, APPLICATION_METADATA))
                or matches_prefix(reference, (*TECHNOLOGY, *SHARED_ADAPTERS)))
    if source_layer == "transport":
        return matches_prefix(reference, DATABASE)
    return False


def without_comments(text):
    """Keep strings intact, including interpolation; strip nested Kotlin comments."""
    out, index, depth = [], 0, 0
    stack = [{"kind": "code", "interpolation": False, "braces": 0}]
    while index < len(text):
        context = stack[-1]
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
        elif context["kind"] == "string":
            quote = context["quote"]
            if text.startswith(quote, index):
                out.append(quote)
                index += len(quote)
                stack.pop()
            elif quote != '"""' and text[index] == "\\":
                out.append(text[index:index + 2])
                index += 2
            elif quote != "'" and text.startswith("${", index):
                out.append("${")
                index += 2
                stack.append({"kind": "code", "interpolation": True, "braces": 0})
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
            stack.append({"kind": "string", "quote": '"""'})
            out.append('"""')
            index += 3
        elif text[index] in "\"'":
            stack.append({"kind": "string", "quote": text[index]})
            out.append(text[index])
            index += 1
        else:
            if context["interpolation"]:
                if text[index] == "{":
                    context["braces"] += 1
                elif text[index] == "}":
                    if context["braces"] == 0:
                        stack.pop()
                    else:
                        context["braces"] -= 1
            out.append(text[index])
            index += 1
    return "".join(out)


def check(root=ROOT):
    errors, modules = [], {}
    folders = [("libs", name) for name in MODULES] + [("services", name) for name in LEGACY_SERVICES]
    for group, name in folders:
        sources = sorted((root / group / name / "src/main/kotlin").rglob("*.kt"))
        if not sources:
            errors.append(f"{name}: boundary check has no Kotlin sources")
        for source in sources:
            # Keep string interpolation visible and normalize legal Kotlin qualification.
            kotlin = without_comments(source.read_text()).replace("`", "")
            package = PACKAGE.search(kotlin)
            source_layer = "pure" if group == "libs" else layer(package[1]) if package else None
            if source_layer not in {"pure", "domain", "application", "transport"}:
                continue
            kotlin = PACKAGE.sub("", kotlin)
            text = re.sub(r"\s*\.\s*", ".", kotlin)
            for reference in sorted(set(QUALIFIED.findall(text))):
                if forbidden_reference(source_layer, reference):
                    errors.append(f"{source.relative_to(root)}: {source_layer} depends on {reference}")
        modules[name] = len(sources)
    return {"modules": modules, "errors": errors, "scope": "production source; Gradle separately checks pure-core classpaths"}


def bytecode_errors(output):
    errors = set()
    for line in output.splitlines():
        edge = JDEPS_EDGE.match(line)
        if edge:
            source, reference = edge.groups()
            if forbidden_reference(layer(source.rsplit(".", 1)[0]), reference):
                errors.add(f"{source} depends on {reference}")
    return sorted(errors)


def check_bytecode(root, jdeps):
    errors = []
    for name in LEGACY_SERVICES:
        classes = root / "services" / name / "build/classes/kotlin/main"
        if not any(classes.rglob("*.class")):
            errors.append(f"{name}: no production Kotlin classes for boundary verification")
            continue
        result = subprocess.run([jdeps, "--ignore-missing-deps", "-verbose:class", "-filter:none", str(classes)],
                                capture_output=True, text=True)
        if result.returncode:
            errors.append(f"{name}: jdeps failed ({result.returncode}): {(result.stderr or result.stdout).strip()}")
            continue
        errors.extend(f"{name}: {error}" for error in bytecode_errors(result.stdout))
    return errors


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdeps", help="Path to jdeps for checking compiled production classes")
    args = parser.parse_args()
    report = check()
    if args.jdeps:
        report["errors"].extend(check_bytecode(ROOT, args.jdeps))
        report["scope"] += "; legacy production bytecode"
    print(json.dumps(report, sort_keys=True))
    raise SystemExit(bool(report["errors"]))
