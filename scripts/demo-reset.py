#!/usr/bin/env python3
"""Reset only an explicitly confirmed local demo project, then verify its empty application store."""
import argparse
from datetime import datetime, timezone
import json
import subprocess
from demo_environment import DemoEnvironment, ROOT

parser = argparse.ArgumentParser(description="Удалить данные только отдельного cabinet-demo* проекта и вернуть начальное демо-состояние")
parser.add_argument("--project", required=True)
parser.add_argument("--confirm-project", required=True, help="Повторите точное имя проекта, все его данные будут удалены")
parser.add_argument("--port", type=int, default=18082)
parser.add_argument("--no-build", action="store_true")
args = parser.parse_args()
if args.project != args.confirm_project:
    parser.error("Подтверждение должно точно совпадать с именем проекта")
environment = DemoEnvironment(args.project, args.port)
environment.assert_demo()
environment.compose("down", "-v", "--remove-orphans")
environment.compose("up", "-d", "--wait", "--wait-timeout", "300", "--no-build" if args.no_build else "--build")
identifiers = subprocess.check_output(["docker", "ps", "-q", "--filter", "label=com.docker.compose.project=" + args.project,
                                       "--filter", "label=com.docker.compose.service=postgres"], text=True).split()
assert len(identifiers) == 1
count = subprocess.check_output(["docker", "exec", identifiers[0], "psql", "-U", "cabinet", "-d", "credit", "-tAc", "select count(*) from applications"], text=True).strip()
assert count == "0", "Начальное состояние не восстановлено: в БД остались заявки"
report = {"checked_at": datetime.now(timezone.utc).isoformat(), "project": args.project, "applications": 0, "passed": True}
directory = ROOT / "scripts/demo-reports"
directory.mkdir(exist_ok=True)
(directory / ("reset-" + args.project + ".json")).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(report, ensure_ascii=False))
