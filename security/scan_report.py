"""Summarize scan gates while retaining only safe locations for secret findings."""
from collections import Counter
from copy import deepcopy


def sanitize(report):
    safe = deepcopy(report)
    vulnerabilities = []
    secrets = []
    accepted = []
    for result in safe.get("Results", []):
        vulnerabilities.extend(result.get("Vulnerabilities", []))
        accepted.extend(result.get("ExperimentalModifiedFindings", []))
        values = result.get("Secrets", [])
        secrets.extend(values)
        if values:
            # A whitelist also excludes unexpected secret-bearing fields in future formats.
            result["Secrets"] = [{key: finding[key] for key in ["RuleID", "Category", "Severity", "Title", "StartLine", "EndLine"] if key in finding}
                                 for finding in values]
    critical = [item for item in vulnerabilities if item["Severity"] == "CRITICAL" and item.get("FixedVersion")]
    summary = {"severity_counts": dict(Counter(item["Severity"] for item in vulnerabilities)),
               "unique_vulnerability_ids": len({item["VulnerabilityID"] for item in vulnerabilities}),
               "suppressed_findings": len(accepted), "secret_findings": len(secrets),
               "fixable_critical": [{"id": item["VulnerabilityID"], "package": item["PkgName"],
                                     "installed": item["InstalledVersion"], "fixed": item["FixedVersion"]} for item in critical],
               "passed": not critical and not secrets}
    return safe, summary
