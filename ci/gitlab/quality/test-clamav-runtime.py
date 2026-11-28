#!/usr/bin/env python3
"""Exercise seed admission with controlled sigtool answers and no Docker daemon."""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("clamav-runtime.sh")


class ClamavSeedTest(unittest.TestCase):
    def test_160_hour_age_and_integrity_gates(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            serial = "28194"
            seed = root / serial
            seed.mkdir()
            data = b"synthetic CVD bytes"
            (seed / "daily.cvd").write_bytes(data)
            signature = seed / f"daily-{serial}.cvd.sign"
            signature.write_bytes(b"synthetic detached signature")
            command = root / "runtime.sh"
            command.write_text(SCRIPT.read_text().replace("/var/lib/pump-ci/clamav-official", str(root)))
            realpath = root / "realpath"
            realpath.write_text('#!/bin/sh\n[ "$1" = -e ] && [ "$2" = -- ] && [ -d "$3" ] && printf "%s\\n" "$3"\n')
            stub = root / "docker"
            stub.write_text("""#!/bin/sh
set -eu
case "$1" in
run)
    case "$*" in
        *"sigtool --cvdcertsdir /etc/clamav/certs --verify /seed/daily.cvd"*) ;;
        *) exit 3;;
    esac
    [ "$SEED_TEST_SIGNATURE" = valid ] || exit 4
    printf 'Version: %s\\nBuild time: 01 Jan 2000 00:00:00 +0000\\nVerification OK.\\n' "$SEED_TEST_SERIAL"
    ;;
build)
    printf '%s\\n' built > "$SEED_TEST_BUILD"
    ;;
*) exit 5;;
esac
""")
            date = root / "date"
            date.write_text("""#!/bin/sh
set -eu
case "$*" in
  '-u -d 01 Jan 2000 00:00:00 +0000 +%s') echo 946684800 ;;
  '-u +%s') echo "$((946684800 + SEED_TEST_AGE))" ;;
  *) exit 6 ;;
esac
""")
            for path in (realpath, stub, date):
                path.chmod(0o700)
            marker = root / "build-marker"
            config = {
                "PATH": str(root) + os.pathsep + os.environ.get("PATH", os.defpath),
                "CI_JOB_ID": "12",
                "CABINET_CI_CLAMAV_SEED_FROM_FILES": "1",
                "CABINET_CI_CLAMAV_SEED_SERIAL": serial,
                "CABINET_CI_CLAMAV_SEED_SHA256": hashlib.sha256(data).hexdigest(),
                "SEED_TEST_SERIAL": serial,
                "SEED_TEST_AGE": "345600",
                "SEED_TEST_SIGNATURE": "valid",
                "SEED_TEST_BUILD": str(marker),
                "TMPDIR": str(root),
            }

            def run(**overrides):
                marker.unlink(missing_ok=True)
                return subprocess.run(["bash", command], env=config | overrides, capture_output=True, text=True, check=False)

            for age in ("345600", "576000"):
                with self.subTest(age=age):
                    result = run(SEED_TEST_AGE=age)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertTrue(marker.exists())
            for overrides in (
                {"SEED_TEST_AGE": "576001"},
                {"SEED_TEST_AGE": "-301"},
                {"SEED_TEST_SIGNATURE": "invalid"},
                {"SEED_TEST_SERIAL": "28193"},
                {"CABINET_CI_CLAMAV_SEED_SHA256": "0" * 64},
            ):
                with self.subTest(overrides=overrides):
                    self.assertNotEqual(run(**overrides).returncode, 0)
                    self.assertFalse(marker.exists())
            signature.unlink()
            self.assertNotEqual(run().returncode, 0)
            self.assertFalse(marker.exists())
            (seed / "daily.cvd").unlink()
            self.assertNotEqual(run().returncode, 0)
            self.assertFalse(marker.exists())


if __name__ == "__main__":
    unittest.main()
