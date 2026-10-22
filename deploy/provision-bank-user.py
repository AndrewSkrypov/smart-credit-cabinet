#!/usr/bin/env python3
"""Provision a bank operator with a private password through the scoped database maintenance channel."""
import argparse
import getpass
import re
import sys
import uuid
import bcrypt
from common import Deployment

parser = argparse.ArgumentParser(description="Создать отдельную учётку банка без общего демо-пароля")
parser.add_argument("--project", default="cabinet-prod")
parser.add_argument("--env-file", default="deploy/.env")
parser.add_argument("--local", action="store_true")
parser.add_argument("--email", required=True)
parser.add_argument("--name", required=True)
parser.add_argument("--role", required=True, choices=["MANAGER", "UNDERWRITER", "RISK_ADMIN"])
parser.add_argument("--password-stdin", action="store_true", help="Секрет из stdin для изолированных проверок; иначе скрытый интерактивный ввод")
args = parser.parse_args()
deployment = Deployment(args.project, args.env_file, args.local)
if not re.fullmatch(r"[A-Za-z0-9_.+-]+@[A-Za-z0-9.-]+", args.email) or args.email.lower().endswith("@demo.test"):
    parser.error("Нужна отдельная почта оператора, отличная от демо-учёток")
if not args.name.strip() or len(args.name) > 255 or any(ord(char) < 32 for char in args.name):
    parser.error("Некорректное имя")
password = sys.stdin.readline().rstrip("\r\n") if args.password_stdin else getpass.getpass("Новый пароль (16–72 байта UTF-8): ")
encoded = password.encode("utf-8")
if not 16 <= len(encoded) <= 72:
    parser.error("Пароль должен содержать 16–72 байта UTF-8")
if not args.password_stdin and getpass.getpass("Повторите пароль: ") != password:
    parser.error("Пароли не совпадают")
password_hash = bcrypt.hashpw(encoded, bcrypt.gensalt(rounds=12)).decode()


def literal(value):
    return "'" + value.replace("'", "''") + "'"


identifier = str(uuid.uuid4())
deployment.sql("identity", f"""
    set standard_conforming_strings=on;
    insert into users(id, full_name, email, role, status, company_id, can_sign, password_hash)
    values ('{identifier}', {literal(args.name.strip())}, {literal(args.email.lower())}, '{args.role}', 'ACTIVE', null, false, {literal(password_hash)});
""")
print(f"Создан отдельный оператор {args.role}; пароль и хэш не выводятся. Повторный запуск не перезаписывает существующую учётку.")
