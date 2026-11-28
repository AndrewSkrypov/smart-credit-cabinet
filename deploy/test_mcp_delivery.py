"""Local proofs for the opt-in Compose delivery of MCP."""
from hashlib import sha256
import json
import os
from pathlib import Path
import shutil
import subprocess
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import common
import mcp_dev
from test_dev_payment_reserve_delivery import environment

ROOT = Path(__file__).resolve().parents[1]


class McpDeliveryTest(unittest.TestCase):
    def setUp(self):
        self.directory = TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.state = Path(self.directory.name)
        self.release = SimpleNamespace(
            revision="1" * 40, alpha_dev=None, assessment=None, servicing=None, documents=None,
            images={"mcp": "registry.example.invalid/pump/mcp@sha256:" + "2" * 64},
            environment=lambda: {"CABINET_IMAGE_MCP": self.release.images["mcp"]},
        )
        self.values = {
            **environment(),
            "CABINET_MCP_OAUTH_CLIENT_ID": "local-client",
            "CABINET_MCP_OAUTH_CLIENT_NAME": "Synthetic client",
            "CABINET_MCP_OAUTH_REDIRECT_URI": "http://127.0.0.1:65100/return",
            "CABINET_MCP_IDENTITY_SERVICE_TOKEN": "A" * 48,
            "CABINET_MCP_CREDIT_SERVICE_TOKEN": "B" * 48,
        }

    def deploy(self):
        env = "".join(f"{key}={value}\n" for key, value in self.values.items()).encode()
        with patch.object(common, "RegistryRelease", return_value=self.release), patch.object(
            common, "requested_mode", return_value="disabled",
        ), patch.object(mcp_dev, "check_tls", return_value="3" * 64):
            return common.Deployment("cabinet-dev", self.state / "cabinet.env",
                                     release_manifest=self.state / "release.json", env_contents=env, open_hooks=False)

    def test_disabled_by_default_and_ambient_enable_ignored(self):
        with patch.dict(os.environ, {mcp_dev.FLAG: "true"}):
            deployment = self.deploy()
        self.assertFalse(deployment.mcp)
        self.assertFalse(any(mcp_dev.OVERLAY in arg for arg in deployment.command))

    def test_opt_in_requires_normal_dev_and_pinned_image(self):
        self.values[mcp_dev.FLAG] = "true"
        deployment = self.deploy()
        self.assertTrue(deployment.mcp)
        self.assertEqual(deployment.command.count(str(ROOT / mcp_dev.OVERLAY)), 1)
        self.assertEqual(deployment.environment["CABINET_IMAGE_MCP"], self.release.images["mcp"])
        self.assertFalse(mcp_dev.enabled("cabinet-dev", False, self.values, self.release, True))
        for project, local in (("cabinet-dev-demo", False), ("cabinet-prod", False), ("cabinet-dev-restore-fixture", False),
                               ("cabinet-dev", True)):
            with self.subTest(project=project, local=local), self.assertRaises(ValueError):
                mcp_dev.enabled(project, local, self.values, self.release, False)
        self.release.images = {}
        with self.assertRaises(ValueError):
            self.deploy()

    def test_reuses_neither_oauth_token_nor_browser_secret(self):
        self.values[mcp_dev.FLAG] = "true"
        self.values["CABINET_MCP_CREDIT_SERVICE_TOKEN"] = self.values["CABINET_MCP_IDENTITY_SERVICE_TOKEN"]
        with self.assertRaises(ValueError):
            self.deploy()
        self.values["JWT_SECRET"] = "B" * 48
        self.values["CABINET_MCP_CREDIT_SERVICE_TOKEN"] = self.values["JWT_SECRET"]
        with self.assertRaises(ValueError):
            self.deploy()

    def test_dev_acceptance_checks_route_auth_and_both_metadata_documents(self):
        origin = "https://dev.credit-cabinet.ru"
        routes = {
            mcp_dev.OAUTH_METADATA: (200, {}, json.dumps({
                "issuer": origin, "scopes_supported": ["cabinet:mcp:read"],
                "code_challenge_methods_supported": ["S256"],
                **{name + "_endpoint": origin + path for name, path in zip(
                    ("authorization", "token", "revocation"), mcp_dev.OAUTH_PATHS, strict=True,
                )},
            }).encode()),
            mcp_dev.MCP_METADATA: (200, {}, json.dumps({
                "resource": origin + mcp_dev.MCP_RESOURCE,
                "authorization_servers": [origin],
                "scopes_supported": ["cabinet:mcp:read"],
            }).encode()),
            mcp_dev.MCP_RESOURCE: (401, {"WWW-Authenticate": f'Bearer resource_metadata="{origin}{mcp_dev.MCP_METADATA}"'}, b""),
            **{path: (status, {}, b"") for path, status in zip(mcp_dev.OAUTH_PATHS, (400, 405, 405), strict=True)},
        }
        def probe(_opener, _origin, route, method="GET"):
            self.assertEqual(_origin, origin)
            self.assertEqual(method, "POST" if route == mcp_dev.MCP_RESOURCE else "GET")
            return routes.get(route, (404, {}, b""))

        with patch.object(mcp_dev, "read_status", side_effect=probe):
            self.assertEqual(mcp_dev.verify_public(None, origin)["private_http"], 404)
            routes[mcp_dev.OAUTH_PATHS[1]] = (200, {}, b"")
            with self.assertRaisesRegex(RuntimeError, "маршрут OAuth"):
                mcp_dev.verify_public(None, origin)

    @unittest.skipUnless(shutil.which("openssl"), "OpenSSL unavailable")
    def test_real_pem_chain_fingerprint_and_required_san(self):
        root = self.state / "mcp-tls"
        root.mkdir(mode=0o700)
        ca = self.state / "ca.crt"
        key = self.state / "ca.key"
        mcp_dev.run_openssl("req", "-new", "-x509", "-nodes", "-newkey", "rsa:2048", "-days", "2",
                            "-subj", "/CN=Synthetic Dev CA", "-keyout", str(key), "-out", str(ca),
                            "-addext", "basicConstraints=critical,CA:TRUE")
        for serial, pair, host in ((1, "identity", "identity-mcp"), (2, "credit", "credit"), (3, "client", None)):
            directory = root / pair
            directory.mkdir()
            leaf, leaf_key = directory / "tls.crt", directory / "tls.key"
            shutil.copyfile(ca, directory / "ca.crt")
            request, extensions = self.state / "request.pem", self.state / "extensions.conf"
            mcp_dev.run_openssl("req", "-new", "-nodes", "-newkey", "rsa:2048",
                                "-subj", f"/CN={host or 'cabinet-mcp'}",
                                "-keyout", str(leaf_key), "-out", str(request))
            extensions.write_text(
                "basicConstraints=critical,CA:FALSE\n"
                + ("extendedKeyUsage=clientAuth\n" if host is None else
                   f"extendedKeyUsage=serverAuth\nsubjectAltName=DNS:{host}\n"),
            )
            mcp_dev.run_openssl("x509", "-req", "-in", str(request), "-CA", str(ca), "-CAkey", str(key),
                                "-set_serial", str(serial), "-days", "1", "-extfile", str(extensions),
                                "-out", str(leaf))
            if pair == "credit":
                credit_request = request.read_bytes()
            for artifact in directory.iterdir():
                artifact.chmod(0o444)
            directory.chmod(0o555)
        expected = sha256(mcp_dev.run_openssl("x509", "-in", str(root / "client/tls.crt"), "-outform", "DER")).hexdigest()
        self.assertEqual(mcp_dev.check_tls(root), expected)

        wrong = self.state / "wrong.crt"
        request.write_bytes(credit_request)
        extensions.write_text("extendedKeyUsage=serverAuth\nsubjectAltName=DNS:wrong\n")
        mcp_dev.run_openssl("x509", "-req", "-in", str(request), "-CA", str(ca), "-CAkey", str(key),
                            "-set_serial", "4", "-days", "1", "-extfile", str(extensions), "-out", str(wrong))
        credit = root / "credit"
        credit.chmod(0o755)
        leaf = credit / "tls.crt"
        leaf.unlink()
        wrong.replace(leaf)
        leaf.chmod(0o444)
        credit.chmod(0o555)
        with self.assertRaisesRegex(ValueError, "DNS SAN"):
            mcp_dev.check_tls(root)

    @unittest.skipUnless(shutil.which("docker"), "Compose executable unavailable")
    def test_compose_preserves_default_routes_and_separates_opt_in_networks(self):
        self.values[mcp_dev.FLAG] = "true"
        deployment = self.deploy()
        required = ("postgres", "identity", "credit", "engagement", "external-sim", "frontend", "swagger")
        image_env = {"CABINET_IMAGE_" + name.upper().replace("-", "_"):
                     f"registry.example.invalid/pump/{name}@sha256:" + "4" * 64 for name in required}
        env = {**deployment.environment, **image_env}
        command = list(deployment.command)
        # --env-file expects an existing file even though the test values come from the process environment.
        (self.state / "cabinet.env").touch()
        config = json.loads(subprocess.check_output(
            [*command, "config", "--format", "json"], cwd=ROOT, env=env, stderr=subprocess.PIPE,
        ))
        services = config["services"]
        self.assertEqual(set(services["identity"]["networks"]), {"cabinet", "mcp-identity-upstream"})
        self.assertIn("cabinet", services["credit"]["networks"])
        self.assertIn("mcp-backplane", services["credit"]["networks"])
        self.assertEqual(set(services["gateway"]["networks"]), {"cabinet", "edge", "mcp-edge"})
        self.assertEqual(set(services["identity-mcp"]["networks"]), {"mcp-identity-upstream", "mcp-backplane"})
        self.assertEqual(set(services["cabinet-mcp"]["networks"]), {"mcp-edge", "mcp-backplane"})
        for name in ("mcp-edge", "mcp-backplane", "mcp-identity-upstream"):
            self.assertTrue(config["networks"][name]["internal"])
        for name in ("cabinet-mcp", "identity-mcp"):
            self.assertFalse(services[name].get("ports"))
        self.assertEqual(services["cabinet-mcp"]["image"], self.release.images["mcp"])
        self.assertNotIn("CABINET_MCP_CREDIT_SERVICE_TOKEN", services["cabinet-mcp"]["environment"])
        self.assertNotIn("CABINET_MCP_IDENTITY_SERVICE_TOKEN", services["credit"]["environment"])
        self.assertNotIn("environment", services["identity-mcp"])
        self.assertTrue(all(v["read_only"] for name in ("cabinet-mcp", "identity-mcp") for v in services[name]["volumes"]))
