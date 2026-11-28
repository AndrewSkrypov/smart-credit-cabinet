#!/usr/bin/env python3
"""Issue private QR cards to the seeded accounts of the isolated jury stand."""

import argparse
import hashlib
import html
import io
import json
import os
import re
import secrets
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

import bcrypt
import qrcode
from qrcode.image.svg import SvgPathImage

PROJECT = "cabinet-jury-atf"
ORIGIN = "https://dev.credit-cabinet.ru"
ENTRY = f"{ORIGIN}/demo/jury"
USER_PREFIX = "9b2f0c4a-6d1e-4f2a-8c3b-"
COMPANY_PREFIX = "6f1d4c1e-0b8e-4c55-9a51-"


@dataclass(frozen=True)
class Seat:
    seed: str
    user: str
    role: str
    company: str | None
    caption: str
    scenario: str

    @property
    def user_id(self):
        return USER_PREFIX + self.user.zfill(12)

    @property
    def company_id(self):
        return COMPANY_PREFIX + self.company.zfill(12) if self.company else None


SEATS = {
    "balbaev": Seat("client", "1", "CLIENT", "1", "Предприниматель", "Подтверждение заявки агента, предложение и график"),
    "adyutantov": Seat("agent", "201", "AGENT", "1", "Агент", "Примерка кредита и передача заявки предпринимателю"),
    "grigoriev": Seat("underwriter", "102", "UNDERWRITER", None, "Андеррайтер", "Ручное рассмотрение заявки и решение"),
    "zhabin": Seat("manager", "101", "MANAGER", None, "Менеджер банка", "Работа с очередью и запрос документов"),
    "zvyagin": Seat("mebel", "2", "CLIENT", "2", "Предприниматель", "Обслуживание кредита, транши и график"),
    "molochnikov": Seat("agent2", "202", "AGENT", "2", "Агент", "Портфель, мандат и комиссия"),
    "ursul": Seat("risk", "103", "RISK_ADMIN", None, "Риск-администратор", "Правила принятия решений и версии продуктов"),
    "agapov": Seat("logistic", "3", "CLIENT", "3", "Предприниматель", "Подбор финансирования и самостоятельная заявка"),
}


def literal(value):
    return "'" + value.replace("'", "''") + "'" if value is not None else "null"


def read_names(path):
    if path.is_symlink() or path.stat().st_mode & 0o077:
        raise ValueError("Файл с именами должен быть обычным закрытым файлом с правами 0600")
    names = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(names, dict) or set(names) != set(SEATS):
        raise ValueError("В файле должен быть по одному имени на каждую из восьми позиций")
    if any(not isinstance(name, str) or not re.fullmatch(r"[А-Яа-яЁёA-Za-z .'-]{2,100}", name)
           for name in names.values()) or len(set(names.values())) != len(SEATS):
        raise ValueError("Проверьте имена участников")
    return names


def sql_statements(names, tokens, expiry, locked_passwords):
    statements = ["begin;", "set local lock_timeout = '5s';",
                  "select pg_advisory_xact_lock(hashtext('cabinet-jury-atf.invites'));",
                  """do $jury$
begin
    if exists (select 1 from users where status = 'ACTIVE' and not jury_link_enabled) then
        raise exception 'Non-jury accounts are active in this database';
    end if;
end
$jury$;"""]
    for slot, token in tokens.items():
        seat = SEATS[slot]
        uid = literal(seat.user_id)
        email = literal(slot + "@jury.test")
        old_email = literal(seat.seed + "@demo.test")
        required_company = literal(seat.company_id if seat.role == "CLIENT" else None)
        token_hash = literal(hashlib.sha256(token.encode("ascii")).hexdigest())
        statements.append(f"""
do $jury$
begin
    update users
       set full_name = {literal(names[slot])}, email = {email}, status = 'ACTIVE',
           jury_link_enabled = true,
           credentials_version = credentials_version + case when jury_link_enabled then 0 else 1 end,
           password_hash = case when jury_link_enabled then password_hash else {literal(locked_passwords[slot])} end
     where id = {uid}::uuid and role = {literal(seat.role)}
       and company_id is not distinct from {required_company}::uuid
       and ((email = {old_email} and status = 'BLOCKED' and not jury_link_enabled)
            or (email = {email} and status = 'ACTIVE' and jury_link_enabled));
    if not found then
        raise exception 'Seeded jury account differs from the expected sandbox state';
    end if;
end
$jury$;
update jury_login_grants set revoked_at = now()
 where user_id = {uid}::uuid and used_at is null and revoked_at is null;
insert into jury_login_grants (token_hash, user_id, credentials_version, expires_at)
select {token_hash}, id, credentials_version, {literal(expiry.isoformat())}::timestamptz
  from users where id = {uid}::uuid;""")
        if seat.role == "AGENT":
            company = literal(seat.company_id)
            statements.append(f"""
update agent_clients
   set status = 'REVOKED', revoked_at = now(), revoked_source = 'LEGACY', revoked_by = null
 where agent_user_id = {uid}::uuid and company_id <> {company}::uuid and status = 'ACTIVE';
insert into agent_clients (id, agent_user_id, company_id)
select gen_random_uuid(), {uid}::uuid, {company}::uuid
 where not exists (
    select 1 from agent_clients where agent_user_id = {uid}::uuid and company_id = {company}::uuid
 );""")
    statements.append("commit;")
    return "\n".join(statements)


def write_card(path, slot, name, token, expiry):
    address = ENTRY + "#" + token
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=8, border=4)
    qr.add_data(address)
    qr.make(fit=True)
    image = io.BytesIO()
    qr.make_image(image_factory=SvgPathImage, fill_color="black", back_color="white").save(image)
    svg = image.getvalue().decode().split("?>", 1)[-1]
    seat = SEATS[slot]
    end = (expiry + timedelta(hours=3)).strftime("%d.%m.%Y %H:%M МСК")
    content = f"""<!doctype html>
<html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="no-referrer">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'">
<title>Кредитный кабинет · {html.escape(name)}</title>
<style>
body {{margin:0;background:#f3f4f6;color:#18191b;font:16px/1.5 system-ui,sans-serif}}
main {{box-sizing:border-box;max-width:440px;margin:24px auto;padding:28px;background:white;border-radius:18px}}
h1 {{font-size:28px;line-height:1.2;margin:10px 0}}
figure {{margin:20px 0;text-align:center}} svg {{width:min(100%,280px);height:auto}}
a {{color:#c52218}} small {{color:#49505b}}
@media print {{body {{background:white}} main {{margin:0 auto;break-inside:avoid}}}}
</style>
<main>
<small>Кредитный кабинет · Демо для жюри</small>
<h1>{html.escape(name)}</h1>
<p><strong>{seat.caption}</strong><br>{seat.scenario}</p>
<figure>{svg}</figure>
<p>Отсканируйте QR или <a href="{html.escape(address, quote=True)}" rel="noreferrer">откройте кабинет</a>.</p>
<small>Приглашение действует до {end} и срабатывает один раз.</small>
</main></html>"""
    path.write_text(content, encoding="utf-8")
    path.chmod(0o600)


def container(service):
    result = subprocess.run(
        ["docker", "ps", "-q", "--filter", f"label=com.docker.compose.project={PROJECT}",
         "--filter", f"label=com.docker.compose.service={service}"],
        check=True, capture_output=True, text=True,
    )
    matches = result.stdout.split()
    if len(matches) != 1:
        raise RuntimeError(f"Ожидается один контейнер {service} проекта {PROJECT}")
    return matches[0]


def prepare_db():
    identity = container("identity")
    result = subprocess.run(["docker", "exec", identity, "printenv", "JURY_LOGIN_ENABLED", "DEMO_ACCOUNTS"],
                            capture_output=True, text=True, check=True)
    if result.stdout.splitlines() != ["true", "false"]:
        raise RuntimeError("Identity должен работать в выделенном режиме жюри")
    return container("postgres")


def write_grants(database, statement):
    result = subprocess.run(
        ["docker", "exec", "-i", database, "psql", "-X", "-U", "cabinet", "-d", "identity",
         "-v", "ON_ERROR_STOP=1", "-q", "-tA"],
        input=statement, capture_output=True, text=True,
    )
    if result.returncode:
        raise RuntimeError("Приглашения не выпущены. Проверьте миграции и состояние синтетических учёток")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--roster", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--hours", type=int, default=48)
    parser.add_argument("--only", choices=sorted(SEATS))
    args = parser.parse_args()
    if not 1 <= args.hours <= 168:
        parser.error("--hours должен быть в диапазоне 1–168")
    if os.path.lexists(args.output):
        parser.error("--output должен указывать на новый каталог")
    names = read_names(args.roster)
    database = prepare_db()
    expiry = datetime.now(timezone.utc) + timedelta(hours=args.hours)
    slots = (args.only,) if args.only else SEATS
    tokens = {slot: secrets.token_urlsafe(32) for slot in slots}
    locked_passwords = {
        slot: bcrypt.hashpw(secrets.token_urlsafe(32).encode(), bcrypt.gensalt(rounds=12)).decode()
        for slot in slots
    }
    staging = Path(tempfile.mkdtemp(prefix=".jury-cards-", dir=args.output.parent))
    published_grants = False
    try:
        for slot, token in tokens.items():
            write_card(staging / (slot + ".html"), slot, names[slot], token, expiry)
        write_grants(database, sql_statements(names, tokens, expiry, locked_passwords))
        published_grants = True
        staging.rename(args.output)
    finally:
        if not published_grants:
            shutil.rmtree(staging)
        if published_grants and staging.exists():
            print(f"Приглашения действуют, карточки остались в закрытом каталоге {staging}")
    print(f"Карточек: {len(tokens)}. Действуют до {expiry.isoformat()}.")


if __name__ == "__main__":
    main()
