"""Opt-in deployment of the named jury demonstration through protected Dev CI."""
from datetime import datetime, timezone
from hashlib import sha256
import json
import os
from pathlib import Path
import re
import secrets
import stat
import subprocess
import sys
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import UUID

from registry_release import unique_fields
from runtime_delivery_support import ensure_private, read_record, write_record

PROJECT = "cabinet-dev-jury"
DOMAIN = "dev.credit-cabinet.ru"
STATE_NAME = "named-jury"
STAGED_BATCH = ".dev-jury-batch.json"
NETWORK = "cabinet-dev-named-jury-edge"
SUBNET = "172.30.42.0/28"
MAX_BATCH_BYTES = 8192
HASH = re.compile(r"[0-9a-f]{64}")
NAME = re.compile(r"[А-Яа-яЁёA-Za-z .'-]{2,100}")
SEATS = {
    # Seed login, user id suffix, role, client/agent company id suffix.
    "balbaev": ("client", 1, "CLIENT", 1),
    "adyutantov": ("agent", 201, "AGENT", 1),
    "grigoriev": ("underwriter", 102, "UNDERWRITER", None),
    "zhabin": ("manager", 101, "MANAGER", None),
    "zvyagin": ("mebel", 2, "CLIENT", 2),
    "molochnikov": ("agent2", 202, "AGENT", 2),
    "ursul": ("risk", 103, "RISK_ADMIN", None),
    "agapov": ("logistic", 3, "CLIENT", 3),
}


def read_batch(path, private=True):
    try:
        metadata = path.lstat()
        if (not stat.S_ISREG(metadata.st_mode) or metadata.st_size > MAX_BATCH_BYTES
                or (private and metadata.st_mode & 0o077)):
            raise ValueError
        batch = json.loads(path.read_bytes(), object_pairs_hook=unique_fields)
        if set(batch) != {"version", "batch_id", "ttl_hours", "seats"} or type(batch["version"]) is not int or batch["version"] != 1:
            raise ValueError
        identifier = UUID(batch["batch_id"])
        if identifier.version != 4 or str(identifier) != batch["batch_id"]:
            raise ValueError
        if type(batch["ttl_hours"]) is not int or not 1 <= batch["ttl_hours"] <= 168 or set(batch["seats"]) != set(SEATS):
            raise ValueError
        names, hashes = set(), set()
        for item in batch["seats"].values():
            if set(item) != {"full_name", "token_hash"}:
                raise ValueError
            name, digest = item["full_name"], item["token_hash"]
            if not NAME.fullmatch(name) or name.strip() != name or not HASH.fullmatch(digest):
                raise ValueError
            names.add(name)
            hashes.add(digest)
        if len(names) != len(SEATS) or len(hashes) != len(SEATS):
            raise ValueError
    except (AttributeError, KeyError, OSError, TypeError, ValueError):
        raise ValueError("Не прошла проверка закрытого пакета для кабинетов жюри") from None
    return batch


def stage(source, target):
    batch = read_batch(source, private=False)
    with os.fdopen(os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w", encoding="utf-8") as stream:
        json.dump(batch, stream, ensure_ascii=False, sort_keys=True)
    print(batch["batch_id"])


def active(state_dir):
    path = state_dir / STATE_NAME
    if not path.exists():
        return None
    ensure_private(path)
    value = read_record(path / "active.json")
    if value is not None and datetime.fromisoformat(value["expires_at"]) > datetime.now(timezone.utc):
        return value
    return None


def run(*command, timeout=120):
    try:
        result = subprocess.run(command, check=True, timeout=timeout, capture_output=True, text=True)
    except (OSError, subprocess.SubprocessError, UnicodeError):
        raise RuntimeError("Не завершена операция изолированного контура жюри") from None
    return result.stdout.strip()


def ensure_network():
    if not run("docker", "network", "ls", "-q", "--filter", f"name=^{NETWORK}$"):
        run("docker", "network", "create", "--internal", "--subnet", SUBNET,
            "--label", "ru.credit-cabinet.scope=named-jury", NETWORK)
    network, = json.loads(run("docker", "network", "inspect", NETWORK))
    permitted = {"cabinet-dev-caddy-1", f"{PROJECT}-gateway-1"}
    if (network["Internal"] is not True or network["IPAM"]["Config"][0]["Subnet"] != SUBNET
            or network["Labels"].get("ru.credit-cabinet.scope") != "named-jury"
            or any(item["Name"] not in permitted for item in (network["Containers"] or {}).values())):
        raise ValueError("Зарезервированная сеть жюри занята другим контуром")


def sql_literal(value):
    return "null" if value is None else "'" + value.replace("'", "''") + "'"


def batch_sql(batch, locked_hash):
    rows = []
    for slot, (seed, user, role, company) in SEATS.items():
        company_id = f"6f1d4c1e-0b8e-4c55-9a51-{company:012d}" if company else None
        identity = batch["seats"][slot]
        row = (f"9b2f0c4a-6d1e-4f2a-8c3b-{user:012d}", role,
               company_id if role == "CLIENT" else None, company_id if role == "AGENT" else None,
               seed + "@demo.test", slot + "@jury.test", identity["full_name"], identity["token_hash"])
        rows.append("(" + ",".join(map(sql_literal, row)) + ")")
    roster = ",\n".join(rows)
    count = len(SEATS)
    return f"""
begin;
set local lock_timeout = '5s';
create temporary table named_jury_batch (
    user_id uuid, role text, client_company uuid, agent_company uuid,
    seed_email text, email text, full_name text, token_hash char(64)
) on commit drop;
insert into named_jury_batch values {roster};

do $named_jury$
declare
    existing integer;
    affected integer;
begin
    perform pg_advisory_xact_lock(hashtext('cabinet-dev-jury.invites'));
    if exists (select 1 from users where status = 'ACTIVE' and not jury_link_enabled) then
        raise exception 'unexpected active users';
    end if;
    -- The Identity redeem transaction needs the user row first, then its grant.
    perform u.id from users u join named_jury_batch s on s.user_id = u.id order by u.id for update of u;
    select count(*) into existing from jury_login_grants g join named_jury_batch s using (token_hash);
    if existing > 0 then
        if existing <> {count} or exists (
            select 1 from jury_login_grants g join named_jury_batch s using (token_hash)
                join users u on g.user_id = u.id
            where g.user_id <> s.user_id or u.email <> s.email or u.full_name <> s.full_name
        ) then
            raise exception 'inconsistent existing grant batch';
        end if;
        return; -- Keep used, revoked and expired grants unchanged on retry.
    end if;

    update users u set full_name = s.full_name, email = s.email, status = 'ACTIVE',
        jury_link_enabled = true,
        credentials_version = credentials_version + case when jury_link_enabled then 0 else 1 end,
        password_hash = case when jury_link_enabled then password_hash else {sql_literal(locked_hash)} end
    from named_jury_batch s
    where u.id = s.user_id and u.role = s.role and u.company_id is not distinct from s.client_company
        and ((u.email = s.seed_email and u.status = 'BLOCKED' and not u.jury_link_enabled)
             or (u.email = s.email and u.status = 'ACTIVE' and u.jury_link_enabled));
    get diagnostics affected = row_count;
    if affected <> {count} then
        raise exception 'unexpected seeded identities';
    end if;

    update jury_login_grants set revoked_at = now()
        where user_id in (select user_id from named_jury_batch) and used_at is null and revoked_at is null;
    insert into jury_login_grants (token_hash, user_id, credentials_version, expires_at)
        select s.token_hash, u.id, u.credentials_version, now() + make_interval(hours => {batch['ttl_hours']})
        from named_jury_batch s join users u on u.id = s.user_id;

    update agent_clients m set status = 'REVOKED', revoked_at = now(), revoked_source = 'LEGACY', revoked_by = null
        from named_jury_batch s where m.agent_user_id = s.user_id and s.role = 'AGENT'
            and m.company_id <> s.agent_company and m.status = 'ACTIVE';
    insert into agent_clients (id, agent_user_id, company_id)
        select gen_random_uuid(), s.user_id, s.agent_company from named_jury_batch s
        where s.role = 'AGENT' and not exists (select 1 from agent_clients m
            where m.agent_user_id = s.user_id and m.company_id = s.agent_company);
end $named_jury$;

select to_char(min(g.expires_at) at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    from jury_login_grants g join named_jury_batch s using (token_hash);
commit;
"""


def activate(root, state_dir, release, parent, host_memory_gib):
    staged = root / STAGED_BATCH
    previous = active(state_dir)
    if not staged.exists():
        return previous
    if (parent.project != "cabinet-dev" or parent.local or parent.values.get("CABINET_DOMAIN") != DOMAIN
            or parent.values.get("CABINET_DEV_PUBLIC_DEMO_EDGE") != "true"):
        raise ValueError("Именные учётки разрешены только на каноническом Dev ATF")
    batch = read_batch(staged)
    if previous is not None and previous["batch_id"] != batch["batch_id"]:
        raise ValueError("Для нового комплекта дождитесь окончания действия текущих приглашений жюри")
    batch_digest = sha256(json.dumps(batch, sort_keys=True).encode()).hexdigest()
    state = state_dir / STATE_NAME
    ensure_private(state)
    issued = state / (batch["batch_id"] + ".json")
    recorded = read_record(issued)
    if recorded is not None:
        if recorded["batch_digest"] != batch_digest:
            raise ValueError("Содержимое ранее выданного пакета жюри изменилось")
        if previous is None or previous["batch_id"] != batch["batch_id"]:
            raise ValueError("Пакет жюри уже истёк или заменён")
        return previous

    ensure_network()
    env = state / "cabinet.env"
    if not env.exists():
        run(sys.executable, str(root / "deploy/configure.py"), "--domain", DOMAIN,
            "--release-manifest", str(release.path), "--output", str(env))
    # Import late: common.Deployment itself configures the parent ingress.
    from bcrypt import gensalt, hashpw
    from common import Deployment

    sandbox = Deployment(PROJECT, env, release_manifest=release.path, open_hooks=False)
    sandbox.command.extend(["-f", str(root / "deploy/compose.jury-atf.yaml")])
    run(sys.executable, str(root / "deploy/render-gateway.py"), "--caddy-ip", "172.30.42.2",
        "--output", str(sandbox.gateway_config))
    services = sandbox.active_services()
    if "caddy" in services or any(service.get("ports") for service in services.values()):
        raise RuntimeError("Контур жюри не может публиковать собственные порты")
    total_budget = sum(int(service["mem_limit"]) for service in services.values())
    total_budget += sum(int(service["mem_limit"]) for service in parent.active_services().values())
    if total_budget > (host_memory_gib - 2) * 1024**3:
        raise RuntimeError("Dev и контур жюри должны оставить 2 GiB для ОС и Docker")
    release.pull_and_verify(services)
    sandbox.compose("up", "-d", "--wait", "--wait-timeout", "300", "--no-build", "gateway")
    locked_hash = hashpw(secrets.token_urlsafe(32).encode(), gensalt(rounds=12)).decode()
    expires_at = sandbox.sql("identity", batch_sql(batch, locked_hash))
    if datetime.fromisoformat(expires_at) <= datetime.now(timezone.utc):
        raise ValueError("Пакет жюри истёк, нужен новый выпуск приглашений")
    return {"batch_id": batch["batch_id"], "batch_digest": batch_digest, "expires_at": expires_at,
            "count": len(SEATS), "revision": release.revision, "manifest_sha256": release.checksum}


def publish(root, state_dir, release, parent, host_memory_gib):
    previous = active(state_dir)
    receipt = activate(root, state_dir, release, parent, host_memory_gib)
    if previous != receipt:
        from common import Deployment

        updated = Deployment(parent.project, parent.path, release_manifest=release.path)
        overlay = str(root / "deploy/compose.dev-jury-edge.yaml")
        if overlay not in updated.command:
            updated.command.extend(["-f", overlay])
        updated.compose("config", "--quiet")
        try:
            updated.compose("up", "-d", "--no-deps", "--wait", "--wait-timeout", "45", "caddy")
            result = verify_route(receipt)
        except (OSError, RuntimeError, ValueError, subprocess.SubprocessError):
            parent.compose("up", "-d", "--no-deps", "--wait", "--wait-timeout", "45", "caddy")
            raise
        state = state_dir / STATE_NAME
        write_record(state / "active.json", receipt)
        write_record(state / (receipt["batch_id"] + ".json"), receipt, immutable=True)
        return result
    return verify_route(receipt)


def verify_route(receipt):
    try:
        with urlopen(f"https://{DOMAIN}/demo/jury/api/v1/auth/options", timeout=15) as response:
            if response.url != f"https://{DOMAIN}/demo/jury/api/v1/auth/options" or response.headers.get_content_type() != "application/json":
                raise ValueError
            options = json.loads(response.read(MAX_BATCH_BYTES))
        if options.get("demoAccounts") is not False:
            raise ValueError
        endpoint = f"https://{DOMAIN}/demo/jury/api/v1/auth/jury-link/prepare"
        unknown = json.dumps({"token": secrets.token_urlsafe(32)}).encode()
        request = Request(endpoint, data=unknown, headers={"Content-Type": "application/json"})
        try:
            with urlopen(request, timeout=15):
                raise ValueError
        except HTTPError as response:
            with response:
                if (response.url != endpoint or response.code != 410 or
                        response.headers.get_content_type() != "application/json" or
                        "no-store" not in {item.strip() for item in response.headers.get("Cache-Control", "").split(",")}):
                    raise ValueError
    except (AttributeError, OSError, ValueError):
        raise RuntimeError("Маршрут изолированных кабинетов жюри не подтверждён") from None
    return {key: receipt[key] for key in ("batch_id", "count", "expires_at")}


if __name__ == "__main__":
    try:
        stage(Path(sys.argv[1]), Path(sys.argv[2]))
    except (OSError, ValueError):
        sys.exit("Не удалось подготовить закрытый пакет для кабинетов жюри")
