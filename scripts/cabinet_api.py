"""Small HTTP client shared by repeatable synthetic cabinet checks."""
import json
import secrets
import time
import re
import subprocess
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from urllib.parse import urlparse


def synthetic_inn():
    base = "00" + f"{secrets.randbelow(10_000_000):07d}"
    return base + str(sum(int(n) * w for n, w in zip(base, [2, 4, 10, 3, 5, 9, 4, 6, 8])) % 11 % 10)


class Cabinet:
    def __init__(self, base="http://localhost:8080", ssl_context=None):
        self.base = base.rstrip("/")
        self.ssl_context = ssl_context

    def request(self, method, path, body=None, token=None, expected=(200,), headers=None, raw=None, timeout=30):
        request_headers = dict(headers or {})
        if token:
            request_headers["Authorization"] = f"Bearer {token}"
        data = raw
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            request_headers["Content-Type"] = "application/json"
        request = Request(self.base + path, method=method, headers=request_headers, data=data)
        started = time.monotonic()
        try:
            response = urlopen(request, timeout=timeout, context=self.ssl_context)
        except HTTPError as error:
            response = error
        with response:
            payload = response.read()
            status = response.status
            response_headers = dict(response.headers)
        try:
            result = json.loads(payload) if payload else None
        except (UnicodeDecodeError, json.JSONDecodeError):
            result = payload.decode("utf-8", errors="replace")
        if expected is not None and status not in expected:
            raise AssertionError(f"{method} {path}: HTTP {status}, ожидалось {expected}: {str(result)[:400]}")
        return {"status": status, "body": result, "headers": response_headers, "duration_ms": (time.monotonic() - started) * 1000}

    def json(self, method, path, body=None, token=None, expected=(200,), **kwargs):
        return self.request(method, path, body, token, expected, **kwargs)["body"]

    def sign_in(self, email, password="Demo2026"):
        return self.json("POST", "/api/v1/auth/sign-in", {"email": email, "password": password})

    def register(self, label):
        inn = synthetic_inn()
        email = f"{label}-{secrets.token_hex(5)}@example.test"
        session = self.json("POST", "/api/v1/auth/sign-up", {
            "inn": inn, "email": email,
            "fullName": "Представитель синтетической компании", "password": "Test2026safe",
            "personalDataConsent": True,
        })
        return {"inn": inn, "token": session["token"], "email": email, "user_id": session["user"]["id"]}

    def create(self, token, amount=1_000_000, term=12, revenue=24_000_000, product="WORKING_CAPITAL", purpose="WORKING_CAPITAL"):
        return self.json("POST", "/api/v1/applications", {
            "productCode": product, "purpose": purpose, "amount": amount, "termMonths": term,
            "declaredRevenue12m": revenue, "declaredMonthlyDebtPayments": 0, "bureauConsent": True,
        }, token, expected=(201,))

    def wait(self, token, application_id, statuses, timeout=180):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            app = self.json("GET", f"/api/v1/applications/{application_id}", token=token)
            if app["status"] in statuses:
                return app
            time.sleep(0.5)
        raise AssertionError(f"Заявка {application_id}: ожидались {statuses}, получено {app['status']}")

    def upload(self, token, application_id, document_type, content, filename="synthetic.pdf", expected=(201,)):
        boundary = "cabinet-" + secrets.token_hex(12)
        raw = (
            f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\n'
            "Content-Type: application/pdf\r\n\r\n"
        ).encode() + content + f"\r\n--{boundary}--\r\n".encode()
        return self.request("POST", f"/api/v1/applications/{application_id}/documents?type={document_type}",
                            token=token, raw=raw, headers={"Content-Type": f"multipart/form-data; boundary={boundary}"}, expected=expected)


class DockerCabinet(Cabinet):
    """Access internal test fixtures only in explicitly named local demo projects."""
    def __init__(self, base="http://localhost:8080", project="cabinet-submission"):
        super().__init__(base)
        if not re.fullmatch(r"cabinet-(?:submission|reliability|e2e)(?:-[a-z0-9]+)?", project):
            raise ValueError("Нужен отдельный синтетический тестовый проект")
        if urlparse(base).hostname not in {"localhost", "127.0.0.1", "::1"}:
            raise ValueError("Внутренние фикстуры разрешены только для локального стенда")
        self.project = project

    def containers(self, service):
        if service not in {"identity", "credit", "engagement", "external-sim", "gateway"}:
            raise ValueError("Неизвестный сервис тестового стенда")
        ids = subprocess.check_output(["docker", "ps", "-q", "--filter", f"label=com.docker.compose.project={self.project}",
                                       "--filter", f"label=com.docker.compose.service={service}"], text=True).split()
        if not ids:
            raise AssertionError(f"Не найден {service} проекта {self.project}")
        return ids

    def service(self, service, method, route, body=None, headers=None, expected=(200,)):
        command = ["docker", "exec", "-i", self.containers(service)[0], "curl", "-sS", "--max-time", "30", "-X", method]
        for key, value in (headers or {}).items():
            command.extend(["-H", f"{key}: {value}"])
        if body is not None:
            command.extend(["-H", "Content-Type: application/json", "--data-binary", "@-"])
        command.extend(["-w", "\n%{http_code}", "http://localhost:8080" + route])
        started = time.monotonic()
        response = subprocess.run(command, input=json.dumps(body, ensure_ascii=False) if body is not None else "",
                                  capture_output=True, text=True, encoding="utf-8", check=False)
        if response.returncode != 0:
            # CalledProcessError would include secret-bearing header arguments in CI output.
            raise AssertionError(f"Сетевой сбой внутреннего {service}: код curl {response.returncode}")
        text, code = response.stdout.rsplit("\n", 1)
        status = int(code)
        if expected is not None and status not in expected:
            raise AssertionError(f"Внутренний {service}: {method} {route}: HTTP {status}, ожидалось {expected}")
        return {"status": status, "body": json.loads(text) if text else None, "duration_ms": (time.monotonic()-started)*1000}

    def test_secret(self, service, variable):
        if variable not in {"JWT_SECRET", "SIM_API_KEY"}:
            raise ValueError("Разрешены только ключи локального тестового протокола")
        # Fixture signing needs the actual local key; keep it in memory and out of every report.
        values = []
        for container in self.containers(service):
            data = json.loads(subprocess.check_output(["docker", "inspect", container], text=True, encoding="utf-8"))[0]
            environment = dict(item.split("=", 1) for item in data["Config"]["Env"] if "=" in item)
            values.append(environment[variable])
        if len(set(values)) != 1:
            raise AssertionError("Ключи экземпляров тестового сервиса различаются")
        return values[0]
