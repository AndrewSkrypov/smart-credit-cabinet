import os
from pathlib import Path
import unittest
from unittest.mock import patch

from postgres_backend import CiPostgres, POSTGRES_IMAGE, tool_environment


class BackendBoundaryTest(unittest.TestCase):
    def test_environment_is_allowlisted_and_proxy_requires_explicit_opt_in(self):
        source = {"PATH": "/tools", "SPRING_DATASOURCE_URL": "external-db", "SPRING_APPLICATION_JSON": "{}", "MIGRATION_ONLY": "true",
                  "PGSERVICE": "foreign", "PGPASSWORD": "foreign-secret", "JWT_SECRET": "foreign-secret", "JAVA_TOOL_OPTIONS": "injected",
                  "HTTP_PROXY": "proxy", "HOME": "/foreign"}
        env = tool_environment(source, Path("/own/home"))
        self.assertEqual({"PATH": "/tools", "HOME": "/own/home", "LANG": "C.UTF-8"}, env)
        self.assertEqual("proxy", tool_environment(source, Path("/own/home"), True)["HTTP_PROXY"])

    def test_non_ci_invocation_never_contacts_any_daemon(self):
        with patch("subprocess.run") as run:
            with self.assertRaises(RuntimeError):
                CiPostgres(POSTGRES_IMAGE, Path("/own"), {}, {})
            run.assert_not_called()

    def test_tcp_socket_is_refused_before_daemon_access(self):
        source = {"CI": "true", "CI_JOB_ID": "123", "CI_PROJECT_ID": "456", "CABINET_CI_DOCKER_HOST": "tcp://foreign:2375"}
        with patch("subprocess.run") as run:
            with self.assertRaises(RuntimeError):
                CiPostgres(POSTGRES_IMAGE, Path("/own"), {}, source)
            run.assert_not_called()

    def test_unlabelled_daemon_is_refused(self):
        source = {"CI": "true", "CI_JOB_ID": "123", "CI_PROJECT_ID": "456", "CABINET_CI_DOCKER_HOST": "unix:///own/docker.sock"}
        with patch.object(Path, "is_socket", return_value=True), patch.object(CiPostgres, "docker", return_value="[]") as docker:
            with self.assertRaises(RuntimeError):
                CiPostgres(POSTGRES_IMAGE, Path("/own"), {}, source)
            self.assertEqual(1, docker.call_count)

    def test_cleanup_rejects_a_container_with_other_component_ownership(self):
        source = {"CI": "true", "CI_JOB_ID": "123", "CI_PROJECT_ID": "456", "CABINET_CI_DOCKER_HOST": "unix:///own/docker.sock"}
        with patch.object(Path, "is_socket", return_value=True), patch.object(CiPostgres, "docker", side_effect=['["cabinet-ci=true"]', '{"cabinet-ci.job":"123","cabinet-ci.project":"456","cabinet-ci.component":"other"}']) as docker:
            backend = CiPostgres(POSTGRES_IMAGE, Path("/own"), {}, source)
            with self.assertRaises(RuntimeError):
                backend.remove("a" * 64)
            self.assertEqual(2, docker.call_count)


if __name__ == "__main__":
    unittest.main()
