import importlib.util
from pathlib import Path
import ssl
import unittest
import urllib.error

spec = importlib.util.spec_from_file_location("https_readiness", Path(__file__).resolve().parents[2] / "deploy/https_readiness.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class HttpsReadinessTest(unittest.TestCase):
    def test_transient_handshakes_recover_with_a_remaining_time_budget(self):
        elapsed, budgets = [0], []
        outcomes = iter([ConnectionResetError(), urllib.error.URLError(ssl.SSLEOFError()), {"status": 200}])
        def request(method, path, timeout):
            self.assertEqual((method, path), ("GET", "/"))
            budgets.append(timeout)
            outcome = next(outcomes)
            if isinstance(outcome, Exception):
                raise outcome
            return outcome
        def sleep(seconds):
            elapsed[0] += seconds
        self.assertEqual({"status": 200}, module.first_response(request, clock=lambda: elapsed[0], sleep=sleep))
        self.assertEqual([30, 29.5, 29], budgets)

    def test_permanent_outage_exhausts_the_budget(self):
        elapsed, calls = [0], []
        def request(method, path, timeout):
            calls.append(timeout)
            elapsed[0] += timeout
            raise TimeoutError("handshake timed out")
        with self.assertRaises(TimeoutError):
            module.first_response(request, timeout=2, clock=lambda: elapsed[0], sleep=lambda _: self.fail("budget exhausted"))
        self.assertEqual([2], calls)

    def test_wrong_ca_or_hostname_is_never_retried(self):
        for wrapped in (False, True):
            with self.subTest(wrapped=wrapped):
                certificate_error = ssl.SSLCertVerificationError("certificate mismatch")
                error = urllib.error.URLError(certificate_error) if wrapped else certificate_error
                def request(*args, **kwargs):
                    raise error
                with self.assertRaises(type(error)):
                    module.first_response(request, sleep=lambda _: self.fail("invalid trust must fail"))

    def test_http_failures_are_not_startup_transport_failures(self):
        def request(*args, **kwargs):
            raise AssertionError("HTTPS response status 503")
        with self.assertRaisesRegex(AssertionError, "503"):
            module.first_response(request, sleep=lambda _: self.fail("must not retry an assertion"))
