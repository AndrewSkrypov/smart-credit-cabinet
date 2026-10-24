"""Real generated-document readiness and authenticated byte checks for restore acceptance."""
import hashlib
import time
import urllib.request

CONTENT_TYPES = {
    "CONTRACT_PDF": "application/pdf",
    "SCHEDULE_PDF": "application/pdf",
    "SCHEDULE_XLSX": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
}


def ready_artifacts(api, route, token, timeout=120):
    deadline = time.monotonic() + timeout
    while True:
        rows = api.json("GET", route + "/artifacts", token=token)
        if len(rows) == 3 and all(row["status"] == "READY" and row["phase"] == "FINAL" for row in rows):
            assert {row["kind"] for row in rows} == set(CONTENT_TYPES), "Не три ожидаемых типа окончательных файлов"
            assert len({row["id"] for row in rows}) == 3, "Повторяющийся ID файла"
            return rows
        assert time.monotonic() < deadline, "Окончательные PDF/XLSX не готовы в отведённое время"
        time.sleep(0.5)


def download_artifacts(api, route, token, rows):
    fingerprints = {}
    for row in rows:
        assert row["status"] == "READY" and row["phase"] == "FINAL"
        assert row["downloadUrl"] == route + "/artifacts/" + row["id"] + "/download", "Неожиданный адрес файла"
        request = urllib.request.Request(api.base + row["downloadUrl"], headers={"Authorization": "Bearer " + token})
        with urllib.request.urlopen(request, context=api.ssl_context, timeout=30) as response:
            content = response.read()
            assert response.headers["Content-Type"] == row["contentType"] == CONTENT_TYPES[row["kind"]]
        checksum = hashlib.sha256(content).hexdigest()
        assert len(content) == row["sizeBytes"] > 0 and checksum == row["checksum"], "Байты файла не совпадают с реестром"
        fingerprints[row["id"]] = {"kind": row["kind"], "phase": row["phase"], "bytes": len(content), "sha256": checksum}
    return fingerprints
