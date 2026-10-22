import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "security"))
from scan_report import sanitize


class ScanReportTest(unittest.TestCase):
    def test_secret_values_are_removed_but_the_gate_still_fails(self):
        value = "synthetic-" + "private-value" * 3
        report = {"Results": [{"Target": "fixture.env", "Secrets": [{
            "RuleID": "fixture-key", "Title": "Test credential", "Severity": "HIGH", "StartLine": 1,
            "Match": value, "Code": {"Lines": [{"Content": f"api_key={value}"}]},
            "FutureField": {"secret": value},
        }]}]}
        safe, gate = sanitize(report)
        self.assertNotIn(value, json.dumps(safe))
        self.assertEqual(safe["Results"][0]["Secrets"][0]["StartLine"], 1)
        self.assertEqual(gate["secret_findings"], 1)
        self.assertFalse(gate["passed"])
        self.assertIn(value, json.dumps(report))

    def test_fixable_critical_vulnerability_blocks_the_gate(self):
        finding = {"Severity": "CRITICAL", "PkgName": "fixture-runtime", "InstalledVersion": "1.0.0",
                   "FixedVersion": "1.0.1", "VulnerabilityID": "CVE-TEST-0001"}
        report = {"Results": [{"Vulnerabilities": [finding]}]}
        safe, gate = sanitize(report)
        self.assertEqual(safe, report)
        self.assertFalse(gate["passed"])
        self.assertEqual(gate["fixable_critical"][0]["fixed"], "1.0.1")

    def test_noncritical_and_explicitly_suppressed_findings_remain_visible(self):
        finding = {"Severity": "HIGH", "PkgName": "fixture-runtime", "InstalledVersion": "1.0.0", "VulnerabilityID": "CVE-TEST-0002"}
        report = {"Results": [{"Vulnerabilities": [finding, finding], "ExperimentalModifiedFindings": [{"Status": "ignored"}]}]}
        safe, gate = sanitize(report)
        self.assertEqual(safe, report)
        self.assertTrue(gate["passed"])
        self.assertEqual(gate["severity_counts"]["HIGH"], 2)
        self.assertEqual(gate["unique_vulnerability_ids"], 1)
        self.assertEqual(gate["suppressed_findings"], 1)


if __name__ == "__main__":
    unittest.main()
