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
LEGACY_SERVICES = ("credit", "documents", "engagement", "identity")
ENTRYPOINTS = {
    "credit": "ru.creditcabinet.CreditServiceApplication",
    "engagement": "ru.creditcabinet.EngagementServiceApplication",
    "identity": "ru.creditcabinet.IdentityServiceApplication",
}
TRANSPORT_LIBRARIES = {"catalog-client", "documents-client", "servicing-grpc"}
ADAPTER_LIBRARIES = {"assessment-messaging", "catalog-jdbc", "documents-renderer", "documents-storage", "servicing-persistence"}
ADAPTERS = {"adapter", "adapters", "config", "dto", "infrastructure", "observability", "persistence"}
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
    "ru.creditcabinet.common.mail.MailCapabilities",
)
HTTP_ERRORS = "ru.creditcabinet.common.exception"
PACKAGE = re.compile(r"(?m)^\s*package\s+([\w.]+)\s*$")
IMPORT = re.compile(r"(?m)^\s*import\s+([\w.]+\*?)(?:\s+as\s+(\w+))?[^\S\n]*$")
DECLARATION = re.compile(r"(?m)^(?:(?:public|private|internal|open|abstract|sealed|data|value|enum|annotation|fun)\s+)*"
                         r"(class|interface|object|typealias)\s+(\w+)")
TYPE_NAME = re.compile(r"\s*((?:[a-zA-Z_]\w*\.)*[a-zA-Z_]\w*)")
STRING = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')
ALIAS = re.compile(r"(?m)^typealias\s+(\w+)\s*=\s*([\w.]+)[^\S\n]*$")
PROTO_PACKAGE = re.compile(r'(?m)^\s*option\s+java_package\s*=\s*"([\w.]+)"\s*;')
QUALIFIED = re.compile(r"\b(?:[a-zA-Z_]\w*\.)+(?:[a-zA-Z_]\w*\b|\*)")
JDEPS_EDGE = re.compile(r"^\s+(\S+)\s+->\s+(\S+)\s+\S")
CONTROLLER_LOCK = re.compile(r"\b(?:forUpdate\s*=\s*true|findForUpdate\s*\()")


def matches_prefix(reference, prefixes):
    return any(reference == prefix or reference.startswith((prefix + ".", prefix + "$")) for prefix in prefixes)


def layer(name):
    parts = name.split(".")
    if parts[:2] != ["ru", "creditcabinet"]:
        return None
    if len(parts) > 2 and parts[2] in {"config", "observability"}:
        return "adapter"
    # A context may be called "application". Treat its nested packages as layers.
    for part in reversed(parts[3:]):
        if part == "domain":
            return "domain"
        if part in {"application", "service"}:
            return "application"
        if part in {"controller", "web", "http", "grpc"}:
            return "transport"
        if part in ADAPTERS:
            return "adapter"
    return None


def forbidden_reference(source_layer, reference, boundaries):
    if reference.endswith(".*"):
        namespace = reference[:-2]
        possible_imports = boundaries.aliases.keys() | (boundaries.http_errors - {HTTP_ERRORS}) | boundaries.adapter_symbols
        if any(symbol.rsplit(".", 1)[0] == namespace and
               forbidden_reference(source_layer, symbol, boundaries) for symbol in possible_imports):
            return True
    reference = boundaries.resolve_alias(reference)
    target_layer = layer(reference.rsplit(".", 1)[0])
    http_error = matches_prefix(reference, boundaries.http_errors)
    external = (matches_prefix(reference, boundaries.transport_packages)
                or matches_prefix(reference, boundaries.adapter_symbols)
                or matches_prefix(reference, ENTRYPOINTS.values()))
    if source_layer in {"pure", "pure-application", "domain"}:
        outer_application = target_layer == "application" and (
            source_layer != "pure-application" or not matches_prefix(reference, boundaries.pure_application_packages)
        )
        return (outer_application or target_layer in {"adapter", "transport"}
                or external or http_error
                or matches_prefix(reference, (*DOMAIN_FRAMEWORK, *TECHNOLOGY, *SHARED_ADAPTERS)))
    if source_layer == "application":
        return (target_layer in {"adapter", "transport"}
                or external or http_error
                or (matches_prefix(reference, DOMAIN_FRAMEWORK) and not matches_prefix(reference, APPLICATION_METADATA))
                or matches_prefix(reference, (*TECHNOLOGY, *SHARED_ADAPTERS)))
    if source_layer == "transport":
        return matches_prefix(reference, (*DATABASE, "org.springframework.transaction"))
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


def kotlin_files(directory):
    return sorted(directory.rglob("src/main/kotlin/**/*.kt"))


def skip_group(text, start, opening, closing):
    depth = 1
    for index in range(start + 1, len(text)):
        if text[index] == opening:
            depth += 1
        elif text[index] == closing:
            depth -= 1
            if not depth:
                return index + 1
    return len(text)


def class_parents(text, start):
    """Read the explicit Kotlin supertype list, skipping generic and constructor arguments."""
    header = text[start:].lstrip()
    if header.startswith("<"):
        header = header[skip_group(header, 0, "<", ">"):].lstrip()
    header = re.sub(r"^(?:(?:private|protected|internal|public)\s+)?constructor\s*", "", header)
    if header.startswith("("):
        header = header[skip_group(header, 0, "(", ")"):].lstrip()
    if not header.startswith(":"):
        return []
    parents = []
    while match := TYPE_NAME.match(header, 1):
        parents.append(match[1])
        header = header[match.end():].lstrip()
        if header.startswith("<"):
            header = header[skip_group(header, 0, "<", ">"):].lstrip()
        if header.startswith("("):
            header = header[skip_group(header, 0, "(", ")"):].lstrip()
        if not header.startswith(","):
            break
    return parents


class BoundaryCatalog:
    def __init__(self, root):
        self.sources, self.transport_packages = {}, set()
        self.adapter_symbols = set()
        self.pure_application_packages = set()
        self.errors = []
        self.aliases, ancestry = {}, {}
        declared, local_sources = set(), []
        for source in kotlin_files(root / "libs") + kotlin_files(root / "services"):
            code = without_comments(source.read_text()).replace("`", "")
            package_match = PACKAGE.search(code)
            package = package_match[1] if package_match else ""
            self.sources[source] = (package, PACKAGE.sub("", code))
            group, module = source.relative_to(root).parts[:2]
            if group == "libs" and module in TRANSPORT_LIBRARIES and package:
                self.transport_packages.add(package)
            if not package:
                continue
            imports = IMPORT.findall(code)
            syntax = STRING.sub('""', code)
            declarations = list(DECLARATION.finditer(syntax))
            symbols = {package + "." + item[2] for item in declarations}
            declared.update(symbols)
            if group == "libs" and module in ADAPTER_LIBRARIES:
                # The renderer shares a package with pure document models. Classify its
                # actual exports without blocking the model package.
                self.adapter_symbols.update(symbols)
                self.adapter_symbols.add(f"{package}.{source.stem}Kt")
            if group == "libs" and module in MODULES and layer(package) == "application":
                self.pure_application_packages.add(package)
            local_sources.append((package, imports, syntax, declarations))
        for package, imported, syntax, declarations in local_sources:
            imports = {alias or name.rsplit(".", 1)[-1]: name for name, alias in imported if not name.endswith("*")}
            star_imports = [name[:-1] for name, _ in imported if name.endswith("*")]
            for item in declarations:
                qualified = package + "." + item[2]
                if item[1] == "typealias":
                    alias = re.match(r"\s*=\s*([\w.]+)", syntax[item.end():])
                    if alias:
                        self.aliases[qualified] = self.resolve_name(alias[1], package, imports, star_imports, declared)
                elif item[1] == "class":
                    ancestry[qualified] = [
                        self.resolve_name(parent, package, imports, star_imports, declared)
                        for parent in class_parents(syntax, item.end())
                    ]
        for schema in sorted((root / "contracts/proto").rglob("*.proto")):
            package = PROTO_PACKAGE.search(without_comments(schema.read_text()))
            if package:
                self.transport_packages.add(package[1])
            else:
                self.errors.append(f"{schema.relative_to(root)}: generated transport needs an explicit java_package")
        for module in TRANSPORT_LIBRARIES:
            if not any(path.relative_to(root).parts[:2] == ("libs", module) and package for path, (package, _) in self.sources.items()):
                self.errors.append(f"{module}: transport library has no production package")
        if not any((root / "contracts/proto").rglob("*.proto")):
            self.errors.append("contracts/proto: no production wire packages")
        self.http_errors = {HTTP_ERRORS}
        # A foreign subtype remains a transport type even when declared in another package.
        pending = set(ancestry)
        while inherited := {child for child in pending if any(
                matches_prefix(self.resolve_alias(parent), self.http_errors) for parent in ancestry[child])}:
            self.http_errors.update(inherited)
            pending.difference_update(inherited)

    @staticmethod
    def resolve_name(name, package, imports, star_imports, declared):
        first, dot, rest = name.partition(".")
        if first in imports:
            return imports[first] + dot + rest
        if dot and first[0].islower():
            return name
        local = package + "." + name
        if package + "." + first in declared:
            return local
        return next((namespace + name for namespace in star_imports if namespace + first in declared), local)

    def resolve_alias(self, reference):
        seen = set()
        while True:
            prefix, suffix = reference, ""
            while prefix and prefix not in self.aliases:
                prefix, separator, last = prefix.rpartition(".")
                suffix = separator + last + suffix
            if not prefix or prefix in seen:
                return reference
            seen.add(prefix)
            reference = self.aliases[prefix] + suffix


def composition_or_type_aliases(service, source, package, code):
    qualified = f"{package}.{source.stem}"
    if qualified == ENTRYPOINTS.get(service) and "@SpringBootApplication" in code:
        return "composition"
    # Compatibility packages may contain type aliases only. Aliases used by core are resolved
    # to the target type before checking its layer. A new declaration here requires a layer.
    if ALIAS.search(code) and not ALIAS.sub("", code).strip():
        return "aliases"
    return None


def check(root=ROOT):
    boundaries = BoundaryCatalog(root)
    errors, modules = sorted(boundaries.errors), {}
    folders = [("libs", name) for name in MODULES] + [("services", name) for name in LEGACY_SERVICES]
    for group, name in folders:
        sources = sorted((root / group / name / "src/main/kotlin").rglob("*.kt"))
        if not sources:
            errors.append(f"{name}: boundary check has no Kotlin sources")
        for source in sources:
            package, kotlin = boundaries.sources[source]
            source_layer = layer(package)
            if group == "libs":
                source_layer = "pure-application" if source_layer == "application" else "pure"
            if source_layer is None and group == "services":
                source_layer = composition_or_type_aliases(name, source, package, kotlin)
            if source_layer is None:
                errors.append(f"{source.relative_to(root)}: unclassified production package {package or '<missing>'}")
                continue
            if source_layer not in {"pure", "pure-application", "domain", "application", "transport"}:
                continue
            text = re.sub(r"\s*\.\s*", ".", kotlin)
            for reference in sorted(set(QUALIFIED.findall(text))):
                if forbidden_reference(source_layer, reference, boundaries):
                    errors.append(f"{source.relative_to(root)}: {source_layer} depends on {reference}")
            if source_layer == "transport" and CONTROLLER_LOCK.search(STRING.sub('""', kotlin)):
                errors.append(f"{source.relative_to(root)}: transport acquires a business write lock")
        modules[name] = len(sources)
    return {"modules": modules, "errors": errors, "scope": "production source; Gradle separately checks pure-core classpaths"}


def bytecode_errors(output, boundaries=None, pure_module=False):
    boundaries = boundaries or BoundaryCatalog(ROOT)
    errors = set()
    for line in output.splitlines():
        edge = JDEPS_EDGE.match(line)
        if edge:
            source, reference = edge.groups()
            source_layer = layer(source.rsplit(".", 1)[0])
            if pure_module:
                source_layer = "pure-application" if source_layer == "application" else "pure"
            if forbidden_reference(source_layer, reference, boundaries):
                errors.add(f"{source} depends on {reference}")
    return sorted(errors)


def check_bytecode(root, jdeps):
    boundaries = BoundaryCatalog(root)
    errors = []
    modules = [("libs", name) for name in MODULES] + [("services", name) for name in LEGACY_SERVICES]
    for group, name in modules:
        module = root / group / name
        classdirs = [module / f"build/classes/{language}/main" for language in ("kotlin", "java")]
        classdirs = [directory for directory in classdirs if any(directory.rglob("*.class"))]
        if not classdirs:
            errors.append(f"{name}: no production classes for boundary verification")
            continue
        result = subprocess.run([jdeps, "--ignore-missing-deps", "-verbose:class", "-filter:none", *map(str, classdirs)],
                                capture_output=True, text=True)
        if result.returncode:
            errors.append(f"{name}: jdeps failed ({result.returncode}): {(result.stderr or result.stdout).strip()}")
            continue
        if group == "services":
            entrypoint = ENTRYPOINTS.get(name)
            allowed_roots = {entrypoint, entrypoint + "Kt"} if entrypoint else set()
            for source in (module / "src/main/kotlin").rglob("*.kt"):
                package, code = boundaries.sources[source]
                if composition_or_type_aliases(name, source, package, code) == "aliases":
                    allowed_roots.add(f"{package}.{source.stem}Kt")
            for directory in classdirs:
                for bytecode in directory.rglob("*.class"):
                    class_name = ".".join(bytecode.relative_to(directory).with_suffix("").parts)
                    if layer(class_name.rsplit(".", 1)[0]) is None and class_name.split("$", 1)[0] not in allowed_roots:
                        errors.append(f"{name}: unclassified production class {class_name}")
        errors.extend(f"{name}: {error}" for error in bytecode_errors(result.stdout, boundaries, pure_module=group == "libs"))
    return errors


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdeps", help="Path to jdeps for checking compiled production classes")
    args = parser.parse_args()
    report = check()
    if args.jdeps:
        report["errors"].extend(check_bytecode(ROOT, args.jdeps))
        report["scope"] += "; production bytecode for pure libraries and guarded services"
    print(json.dumps(report, sort_keys=True))
    raise SystemExit(bool(report["errors"]))
