#!/usr/bin/env python3
"""Create private owner cards and a separate hash-only Dev provisioning file."""

import argparse
import hashlib
import html
import io
import json
import os
import re
import secrets
import uuid
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont
import qrcode


ENTRY_URL = "https://dev.credit-cabinet.ru/demo/jury"
CARDS = {
    "balbaev": ("Предприниматель", "Подтверждение заявки и график"),
    "adyutantov": ("Агент", "Примерка кредита и передача заявки"),
    "grigoriev": ("Андеррайтер", "Ручное рассмотрение и решение"),
    "zhabin": ("Менеджер банка", "Очередь и запрос документов"),
    "zvyagin": ("Предприниматель", "Обслуживание кредита и транши"),
    "molochnikov": ("Агент", "Портфель, мандат и комиссия"),
    "ursul": ("Риск-администратор", "Правила и версии продуктов"),
    "agapov": ("Предприниматель", "Подбор кредита и новая заявка"),
}


def load_roster(path):
    if path.is_symlink() or not path.is_file() or path.stat().st_mode & 0o077:
        raise ValueError("Файл участников должен иметь права 0600")
    roster = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(roster, dict) or roster.keys() != CARDS.keys():
        raise ValueError("В списке должны быть восемь позиций жюри")
    if any(not isinstance(name, str) or not re.fullmatch(r"[А-Яа-яЁёA-Za-z .'-]{2,100}", name)
           for name in roster.values()) or len(set(roster.values())) != len(CARDS):
        raise ValueError("Проверьте имена участников")
    return roster


def card_image(name, role, scenario, address, font_path):
    code = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, border=4, box_size=8)
    code.add_data(address)
    qr = code.make_image(fill_color="black", back_color="white").convert("RGB")

    canvas = Image.new("RGB", (720, 790), "#f3f4f6")
    draw = ImageDraw.Draw(canvas)
    draw.rounded_rectangle((24, 24, 696, 766), radius=28, fill="white")
    draw.rounded_rectangle((58, 68, 72, 94), radius=5, fill="#e52718")
    for text, point, size, color in (
        ("Кредитный кабинет · Жюри", (86, 70), 24, "#40444b"),
        (name, (58, 116), 41, "#151619"),
        (role, (58, 179), 27, "#b51d13"),
        (scenario, (58, 220), 22, "#40444b"),
        ("Отсканируйте QR и нажмите «Войти».", (58, 710), 25, "#151619"),
    ):
        draw.text(point, text, font=ImageFont.truetype(str(font_path), size), fill=color)
    canvas.paste(qr, ((canvas.width - qr.width) // 2, 266))
    output = io.BytesIO()
    canvas.save(output, format="PNG", optimize=True)
    return output.getvalue()


def prepare(roster, output, hours, font_path):
    batch = {"version": 1, "batch_id": str(uuid.uuid4()), "ttl_hours": hours, "seats": {}}
    links = []
    output.mkdir(mode=0o700)
    with zipfile.ZipFile(output / "QR для жюри.zip", "x", compression=zipfile.ZIP_DEFLATED) as archive:
        for number, (slot, (role, scenario)) in enumerate(CARDS.items(), start=1):
            name = roster[slot]
            token = secrets.token_urlsafe(32)
            batch["seats"][slot] = {
                "full_name": name,
                "token_hash": hashlib.sha256(token.encode("ascii")).hexdigest(),
            }
            address = ENTRY_URL + "#" + token
            archive.writestr(f"{number:02d} {name}.png", card_image(name, role, scenario, address, font_path))
            links.append(
                f'<li><a rel="noreferrer" href="{html.escape(address, quote=True)}">'
                f'{html.escape(name)}</a> · {html.escape(role)}</li>'
            )
        archive.writestr("Открыть кабинеты.html", """<!doctype html><html lang="ru">
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>Кабинеты жюри</title>
<style>body{max-width:42rem;margin:2rem auto;padding:0 1rem;font:17px/1.8 system-ui}a{color:#b51d13}</style>
<h1>Кабинеты жюри</h1><p>Ссылки действуют после подключения комплекта на Dev.</p><ul>"""
                         + "\n".join(links) + "</ul></html>")
    (output / "dev-jury-batch.json").write_text(json.dumps(batch, ensure_ascii=False, indent=2) + "\n",
                                                encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--roster", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--hours", default=48, type=int, choices=range(1, 169), metavar="1–168")
    parser.add_argument("--font", type=Path, default=Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"))
    args = parser.parse_args()
    roster = load_roster(args.roster)
    os.umask(0o077)
    prepare(roster, args.output, args.hours, args.font)
    print("Подготовлено восемь QR-карточек и файл активации с хешами")
