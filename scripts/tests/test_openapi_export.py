import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import yaml

spec = importlib.util.spec_from_file_location("openapi_export", Path(__file__).resolve().parents[1] / "export-openapi.py")
export = importlib.util.module_from_spec(spec)
spec.loader.exec_module(export)


class OpenApiExportTest(unittest.TestCase):
    def document(self):
        return {
            "openapi": "3.1.0",
            "servers": [{"url": "http://localhost:8080", "description": "Generated server url"}],
            "paths": {"/api/v1/products": {"get": {"summary": "Продукты", "responses": {"200": {"description": "OK"}}}}},
            "components": {"securitySchemes": {"bearerAuth": {"type": "http", "scheme": "bearer"}}},
        }

    def test_only_request_origin_is_normalized(self):
        original = self.document()
        changed = copy.deepcopy(original)
        changed["servers"][0]["url"] = "https://different-test-host.example"
        self.assertEqual(export.canonical(original), export.canonical(changed))
        self.assertEqual(original["servers"][0]["url"], "http://localhost:8080")
        changed["paths"]["/api/v1/products"]["get"]["responses"]["403"] = {"description": "Forbidden"}
        self.assertNotEqual(export.canonical(original), export.canonical(changed))

    def test_hidden_internal_paths_are_rejected(self):
        document = self.document()
        document["paths"]["/internal/v1/users"] = {"get": {"summary": "Внутренний API"}}
        with self.assertRaisesRegex(ValueError, "внутренний путь"):
            export.validate(document, "identity")

    def test_explicit_server_contract_is_preserved(self):
        document = self.document()
        document["servers"] = [{"url": "https://api.example/v2", "description": "Явный адрес API"}]
        self.assertEqual(export.canonical(document), document)

    def test_check_fails_when_one_service_contract_changes(self):
        document = self.document()
        with tempfile.TemporaryDirectory() as directory:
            for service in export.SERVICES:
                Path(directory, service + ".yaml").write_text(yaml.safe_dump(export.canonical(document)), encoding="utf-8")
            changed = copy.deepcopy(document)
            changed["paths"]["/api/v1/products"]["get"]["responses"]["409"] = {"description": "Conflict"}

            def response(url, **kwargs):
                return io.StringIO(json.dumps(changed if url.endswith("/credit") else document))

            errors = io.StringIO()
            with patch.object(export.urllib.request, "urlopen", response), patch("sys.argv", ["export-openapi.py", "--check", "--output", directory]):
                with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(errors):
                    result = export.main()
            self.assertEqual(result, 1)
            self.assertIn("409", errors.getvalue())


if __name__ == "__main__":
    unittest.main()
