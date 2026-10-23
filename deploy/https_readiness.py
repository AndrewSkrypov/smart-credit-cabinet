"""Wait for Caddy's site certificate after its local CA has appeared."""
import ssl
import time
import urllib.error


def first_response(request, timeout=30, clock=time.monotonic, sleep=time.sleep):
    deadline = clock() + timeout
    while True:
        remaining = deadline - clock()
        if remaining <= 0:
            raise TimeoutError("Caddy не завершил первый HTTPS-запрос за отведённое время")
        try:
            return request("GET", "/", timeout=remaining)
        except ssl.SSLCertVerificationError:
            raise
        except (urllib.error.URLError, ConnectionError, ssl.SSLError, TimeoutError) as error:
            # A not-yet-issued certificate is transient; a wrong CA or hostname is not.
            if isinstance(getattr(error, "reason", None), ssl.SSLCertVerificationError):
                raise
            if clock() >= deadline:
                raise
            sleep(min(0.5, deadline - clock()))
