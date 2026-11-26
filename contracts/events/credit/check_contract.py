"""Validate ApplicationStatusChanged.v1 fixtures without a running broker."""
import json
import unittest
from datetime import datetime
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker, ValidationError

DIRECTORY = Path(__file__).resolve().parent
FORMATS = FormatChecker()


@FORMATS.checks("date-time", raises=ValueError)
def check_date_time(value):
    # The schema pattern fixes the wire shape. The calendar check is needed even
    # when jsonschema is installed without its optional RFC 3339 dependency.
    if isinstance(value, str):
        datetime.fromisoformat(value.replace("Z", "+00:00"))
    return True


def read_json(relative_path):
    return json.loads((DIRECTORY / relative_path).read_text(encoding="utf-8"))


def validate_key(key, payload):
    if key != payload["applicationId"]:
        raise ValueError("Kafka key must equal applicationId")


class ApplicationStatusChangedContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        schema = read_json("application-status-changed.v1.schema.json")
        Draft202012Validator.check_schema(schema)
        cls.validator = Draft202012Validator(schema, format_checker=FORMATS)
        cls.valid_records = read_json("fixtures/valid.json")
        cls.sample_payload = cls.valid_records[0]["payload"]

    def test_valid_records(self):
        for record in self.valid_records:
            with self.subTest(record=record["name"]):
                self.validator.validate(record["payload"])
                validate_key(record["key"], record["payload"])

    def test_invalid_payloads(self):
        for case in read_json("fixtures/invalid.json"):
            with self.subTest(case=case["name"]):
                field = case["field"]
                payload = {**self.sample_payload, field: case["value"]}
                expected_path = [field] if field in self.sample_payload else []
                self.assertIn(
                    (case["keyword"], expected_path),
                    [(error.validator, list(error.path)) for error in self.validator.iter_errors(payload)],
                )

    def test_every_field_is_required(self):
        for field in self.sample_payload:
            with self.subTest(field=field):
                payload = self.sample_payload.copy()
                del payload[field]
                with self.assertRaises(ValidationError) as result:
                    self.validator.validate(payload)
                self.assertEqual("required", result.exception.validator)

    def test_version_bounds(self):
        for version in (0, 9223372036854775807):
            with self.subTest(version=version):
                self.validator.validate({**self.sample_payload, "applicationVersion": version})

    def test_invalid_kafka_keys(self):
        key = self.sample_payload["applicationId"]
        for wrong_key in (None, "", key.upper(), self.sample_payload["eventId"]):
            with self.subTest(key=wrong_key), self.assertRaises(ValueError):
                validate_key(wrong_key, self.sample_payload)


if __name__ == "__main__":
    unittest.main(verbosity=2)
