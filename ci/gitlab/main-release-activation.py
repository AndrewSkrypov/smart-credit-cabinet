#!/usr/bin/env python3
"""Bind manual production activation to one protected-main release receipt."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import time
from urllib.parse import urlsplit


PROJECT_PATH = "pump/smart-credit-cabinet"
NAMESPACE = "cabinet-prod"
SUBJECT = f"system:serviceaccount:{NAMESPACE}:cabinet-prod-release"
CHECKPOINT = Path("main-release-activation.json")
SHA = re.compile(r"[0-9a-f]{40}")
ID = re.compile(r"[1-9][0-9]*")
UID = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
JWT = re.compile(r"([A-Za-z0-9_-]+)\.([A-Za-z0-9_-]+)\.[A-Za-z0-9_-]+")
ACTIVATOR = "main-release-activation"
DEPLOYERS = {
    "main-dev-deploy": ("2", "dev", "start"),
    "main-prod-deploy": ("4", "production", "start"),
}


class ActivationError(Exception):
    pass


def read_file(path, limit, *, private=False):
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode):
            raise ActivationError("Activation inputs must be regular files.")
        if private and (info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o600):
            raise ActivationError("Protected production inputs require job ownership and mode 0600.")
        data = stream.read(limit + 1)
    if not data or len(data) > limit:
        raise ActivationError("Invalid activation input size.")
    return data


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ActivationError("Duplicate field in activation input.")
        result[key] = value
    return result


def json_object(data):
    value = json.loads(data, object_pairs_hook=unique_object)
    if not isinstance(value, dict):
        raise ActivationError("Activation input must be a JSON object.")
    return value


def checked_job(mode):
    env = os.environ
    if (
        env.get("CI_DEBUG_TRACE", "").casefold() in {"true", "1"}
        or env.get("CI_PROJECT_ID") != "1"
        or env.get("CI_PROJECT_PATH") != PROJECT_PATH
        or env.get("CI_COMMIT_BRANCH") != "main"
        or env.get("CI_COMMIT_REF_NAME") != "main"
        or env.get("CI_COMMIT_REF_PROTECTED") != "true"
        or env.get("CI_PIPELINE_SOURCE") != "push"
        or env.get("PUMP_MAIN_RELEASE_ENABLED") != "1"
    ):
        raise ActivationError("Activation requires the approved protected main push without debug tracing.")
    job = env.get("CI_JOB_ID", "")
    name = env.get("CI_JOB_NAME")
    scope = env.get("CI_RUNNER_ID"), env.get("CI_ENVIRONMENT_NAME"), env.get("CI_ENVIRONMENT_ACTION")
    allowed = {ACTIVATOR: ("4", "production", "prepare")} if mode == "write" else DEPLOYERS
    if not ID.fullmatch(job) or scope != allowed.get(name):
        raise ActivationError("Job, runner, or environment is outside the activation scope.")
    return job, name


def release_binding(job):
    revision = os.environ.get("CI_COMMIT_SHA", "")
    pipeline = os.environ.get("CI_PIPELINE_ID", "")
    if not SHA.fullmatch(revision) or not ID.fullmatch(pipeline):
        raise ActivationError("Invalid activation pipeline identity.")
    manifest = read_file("release.json", 64 * 1024)
    if json_object(manifest).get("revision") != revision:
        raise ActivationError("Release manifest revision does not match the activation pipeline.")
    evidence = json_object(read_file("release-evidence.json", 64 * 1024))
    publisher = evidence.get("publisher_job")
    evidence_fields = {
        "format": 1,
        "project": os.environ["CI_PROJECT_ID"],
        "pipeline": pipeline,
        "revision": revision,
        "publisher_job": publisher,
        "manifest_sha256": hashlib.sha256(manifest).hexdigest(),
    }
    tree = evidence.get("tree")
    if (
        not isinstance(publisher, str) or not ID.fullmatch(publisher) or publisher == job
        or not isinstance(tree, str) or not SHA.fullmatch(tree)
        or type(evidence.get("format")) is not int
        or evidence != evidence_fields | {"ref": "refs/heads/main", "source": "push", "tree": tree}
    ):
        raise ActivationError("Release evidence does not match the activation pipeline and manifest.")
    return {
        "format": 1,
        "project_id": evidence_fields["project"],
        "pipeline_id": pipeline,
        "revision": revision,
        "publisher_job_id": publisher,
        "release_sha256": evidence_fields["manifest_sha256"],
    }


def named_config(config, section, name):
    entries = config.get(section)
    if not isinstance(entries, list) or len(entries) != 1 or not isinstance(entries[0], dict):
        raise ActivationError("Production kubeconfig must select exactly one context, cluster and user.")
    key = {"contexts": "context", "clusters": "cluster", "users": "user"}[section]
    entry = entries[0]
    if set(entry) != {"name", key} or entry.get("name") != name or not isinstance(entry[key], dict):
        raise ActivationError("Production kubeconfig entry does not match its pinned selection.")
    return entry[key]


def production_token(token, now):
    match = JWT.fullmatch(token) if isinstance(token, str) else None
    if not match:
        raise ActivationError("Production kubeconfig requires a static service account JWT.")
    header, claims = (json_object(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))
                      for part in match.groups())
    issued, expiry = claims.get("iat"), claims.get("exp")
    not_before = claims.get("nbf", issued)
    algorithm = header.get("alg")
    if (
        not isinstance(algorithm, str) or not algorithm or algorithm.casefold() == "none"
        or claims.get("sub") != SUBJECT
        or any(type(value) is not int for value in (issued, expiry, not_before))
        or max(issued, not_before) > now
        or not 60 * 60 <= expiry - issued <= 90 * 60
        or expiry - now < 45 * 60
    ):
        raise ActivationError("Production kubeconfig token is outside the approved identity or time window.")


def validate_kubeconfig(data, context, now):
    config = json_object(data)
    if (
        config.get("kind") != "Config" or config.get("apiVersion") != "v1"
        or config.get("current-context") != context
        or set(config) - {"kind", "apiVersion", "current-context", "contexts", "clusters", "users", "preferences"}
        or config.get("preferences", {}) != {}
    ):
        raise ActivationError("Production kubeconfig does not match the approved static format.")
    selection = named_config(config, "contexts", context)
    if set(selection) != {"cluster", "user", "namespace"} or selection.get("namespace") != NAMESPACE:
        raise ActivationError("Production kubeconfig must select the approved namespace.")
    for key in ("cluster", "user"):
        if not isinstance(selection[key], str) or not selection[key]:
            raise ActivationError("Production kubeconfig selection must name a cluster and user.")
    cluster = named_config(config, "clusters", selection["cluster"])
    required_cluster_fields = {"server", "certificate-authority-data"}
    if set(cluster) not in (required_cluster_fields, required_cluster_fields | {"tls-server-name"}):
        raise ActivationError("Production kubeconfig requires the direct HTTPS endpoint and embedded CA.")
    server = cluster["server"]
    endpoint = urlsplit(server) if isinstance(server, str) else None
    if (
        endpoint is None or endpoint.scheme != "https" or not endpoint.hostname or "@" in endpoint.netloc
        or any(character.isspace() for character in server)
        or endpoint.path not in ("", "/") or endpoint.query or endpoint.fragment
        or endpoint.port == 0
        or cluster.get("tls-server-name", endpoint.hostname) != endpoint.hostname
    ):
        raise ActivationError("Invalid production Kubernetes HTTPS endpoint.")
    ca = base64.b64decode(cluster["certificate-authority-data"], validate=True)
    if not ca.startswith(b"-----BEGIN CERTIFICATE-----") or b"-----END CERTIFICATE-----" not in ca:
        raise ActivationError("Production kubeconfig requires an embedded PEM CA certificate.")
    user = named_config(config, "users", selection["user"])
    if set(user) != {"token"}:
        raise ActivationError("Production kubeconfig must use exactly one static token.")
    production_token(user["token"], now)


def validate_jury(data):
    users = json_object(data)
    if set(users) != {"CLIENT", "AGENT", "UNDERWRITER"}:
        raise ActivationError("Production jury file requires the three scoped roles.")
    emails = set()
    passwords = set()
    for record in users.values():
        if not isinstance(record, dict) or set(record) != {"email", "password"}:
            raise ActivationError("Invalid production jury credential shape.")
        email, password = record["email"], record["password"]
        if (
            not isinstance(email, str) or "@" not in email or email != email.strip()
            or not isinstance(password, str) or not 16 <= len(password.encode("utf-8")) <= 72
        ):
            raise ActivationError("Invalid production jury credential shape.")
        emails.add(email.casefold())
        passwords.add(password)
    if len(emails) != len(users) or len(passwords) != len(users):
        raise ActivationError("Production jury roles require separate credentials.")


def verify_namespace(kubeconfig, context, uid):
    result = subprocess.run(
        ["kubectl", "--kubeconfig=/dev/stdin", f"--context={context}", f"--namespace={NAMESPACE}",
         "--request-timeout=10s", "get", "namespace", NAMESPACE, "-o", "jsonpath={.metadata.uid}"],
        input=kubeconfig, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=15, check=False,
    )
    if result.returncode != 0 or result.stdout.strip() != uid.encode("ascii"):
        raise ActivationError("Production namespace identity check failed.")


def validate_production(record):
    env = os.environ
    context = env.get("CABINET_PROD_CONTEXT", "")
    uid = env.get("CABINET_PROD_NAMESPACE_UID", "")
    if not context or env.get("CABINET_PROD_NAMESPACE") != NAMESPACE or not UID.fullmatch(uid):
        raise ActivationError("Production namespace and context pins are required.")
    envelope = json_object(read_file(env["CABINET_MAIN_PROD_JIT_CONTEXT"], 4 * 1024, private=True))
    kubeconfig = read_file(env["CABINET_MAIN_PROD_KUBECONFIG"], 64 * 1024, private=True)
    jury = read_file(env["CABINET_MAIN_PROD_JURY_CREDENTIALS"], 16 * 1024, private=True)
    if type(envelope.get("format")) is not int or envelope != record | {
        "namespace_uid": uid,
        "kubeconfig_sha256": hashlib.sha256(kubeconfig).hexdigest(),
        "jury_sha256": hashlib.sha256(jury).hexdigest(),
    }:
        raise ActivationError("Production JIT files do not match the release and namespace envelope.")
    validate_kubeconfig(kubeconfig, context, int(time.time()))
    validate_jury(jury)
    verify_namespace(kubeconfig, context, uid)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("write", "verify"))
    mode = parser.parse_args().mode
    try:
        job, name = checked_job(mode)
        record = release_binding(job)
        if mode == "write":
            validate_production(record)
            with CHECKPOINT.open("x", encoding="ascii") as output:
                output.write(json.dumps(record | {"checkpoint_job": job}, sort_keys=True) + "\n")
        else:
            saved = json_object(read_file(CHECKPOINT, 4 * 1024))
            checkpoint = saved.pop("checkpoint_job", None)
            if (
                not isinstance(checkpoint, str) or not ID.fullmatch(checkpoint)
                or checkpoint in (job, record["publisher_job_id"])
                or type(saved.get("format")) is not int or saved != record
            ):
                raise ActivationError("Activation checkpoint does not match the current release.")
            if name == "main-prod-deploy":
                validate_production(record)
    except ActivationError as error:
        parser.exit(1, f"Main release activation: {error}\n")
    except (KeyError, OSError, ValueError, TypeError, subprocess.SubprocessError):
        parser.exit(1, "Main release activation could not validate the protected inputs.\n")
    print(f"Main release activation {mode}: verified.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
