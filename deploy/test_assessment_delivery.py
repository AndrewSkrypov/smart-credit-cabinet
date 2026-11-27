import os
from pathlib import Path
import shutil
import socket
import subprocess
from tempfile import TemporaryDirectory
from threading import Thread
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import yaml

import assessment_dev as assessment

ROOT = Path(__file__).resolve().parents[1]
REVISION = "1" * 40
MANIFEST = "2" * 64
JAR = "3" * 64
IMAGE_ID = "sha256:" + "4" * 64


def image(name):
    return f"registry.example.invalid/team/alpha/{name}@sha256:" + "a" * 64


def release(mode="remote", checksum=MANIFEST):
    return SimpleNamespace(revision=REVISION, checksum=checksum,
                           images={"credit": image("credit"), "assessment": image("assessment")},
                           assessment={"jar_sha256": JAR, "dev_mode": mode, "kafka_image": image("kafka")})


class RolloutTest(unittest.TestCase):
    def setUp(self):
        work = TemporaryDirectory()
        self.addCleanup(work.cleanup)
        self.deployment = SimpleNamespace(
            local=False, project="cabinet-dev", path=Path(work.name) / "cabinet.env",
            values={"VALKEY_PASSWORD": "c" * 64}, release=release(),
            compose=Mock(return_value=""), sql=Mock(return_value="1"),
            container=Mock(side_effect=lambda service: service),
        )

    def open(self):
        return assessment.AssessmentDev.open(self.deployment)

    def test_descriptor_and_oci_reject_bad_images_and_jar_hashes(self):
        descriptor = assessment.Descriptor.from_release(release())
        ref, labels = descriptor.image_labels()["assessment"]
        self.assertEqual(labels["org.opencontainers.image.jar.sha256"], JAR)
        for key, value in (("kafka_image", "apache/kafka:latest"), ("kafka_image", image("analytics")),
                           ("jar_sha256", "invalid"), ("dev_mode", "true")):
            with self.subTest(key=key, value=value):
                candidate = release()
                candidate.assessment[key] = value
                with self.assertRaises(ValueError):
                    assessment.Descriptor.from_release(candidate)
        record = {"Id": IMAGE_ID, "RepoDigests": [ref], "Config": {"Labels": dict(labels)}}
        with patch.object(assessment, "one_json", return_value=record):
            self.assertEqual(assessment.verify_image(ref, labels), IMAGE_ID)
            record["Config"]["Labels"]["org.opencontainers.image.jar.sha256"] = "e" * 64
            with self.assertRaisesRegex(RuntimeError, "provenance"):
                assessment.verify_image(ref, labels)
            record["Config"]["Labels"] = labels
            record["RepoDigests"] = [image("credit")]
            with self.assertRaisesRegex(RuntimeError, "provenance"):
                assessment.verify_image(ref, labels)

    def test_automatic_private_secrets_and_stream_survive_a_new_release(self):
        first = self.open()
        path = first.state / "assessment.env"
        self.assertEqual(first.state.stat().st_mode & 0o777, 0o700)
        self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        self.assertEqual(len({first.secrets[key] for key in assessment.PASSWORDS}), 3)
        self.assertNotIn(self.deployment.values["VALKEY_PASSWORD"], first.secrets.values())
        self.deployment.release = release("local", checksum="8" * 64)
        resumed = self.open()
        self.assertEqual((resumed.secrets, resumed.topics, resumed.groups), (first.secrets, first.topics, first.groups))
        self.assertEqual(resumed.compose_environment()["ASSESSMENT_CREDIT_KAFKA_ENABLED"], "false")
        self.deployment.release = release("disabled")
        with self.assertRaisesRegex(RuntimeError, "режиме local"):
            self.open()

    def test_unsafe_env_is_refused_without_rotation(self):
        path = self.open().state / "assessment.env"
        original = path.read_bytes()
        path.chmod(0o644)
        with self.assertRaisesRegex(ValueError, "0600"):
            self.open()
        other = path.with_suffix(".saved")
        path.rename(other)
        path.symlink_to(other)
        with self.assertRaises(OSError):
            self.open()
        self.assertEqual(other.read_bytes(), original)

    def test_receipt_fences_credit_and_verify_is_idempotent(self):
        opened = self.open()
        with self.assertRaisesRegex(RuntimeError, "квитанция готовности"):
            opened.credit_environment({})
        db = ["", opened.generation, "", "1"]
        with (patch.object(assessment, "verify_image", return_value=IMAGE_ID),
              patch.object(assessment.AssessmentDev, "database", side_effect=db),
              patch.object(assessment.AssessmentDev, "ensure_topics") as topics,
              patch.object(assessment.AssessmentDev, "offsets", return_value=0),
              patch.object(assessment.AssessmentDev, "volume_binding", return_value={"existing": True}),
              patch.object(assessment.AssessmentDev, "verify_containers")):
            receipt = opened.prepare()
        topics.assert_called_once_with(False)
        steps = [call.args for call in self.deployment.compose.call_args_list]
        self.assertLess(steps.index(("run", "--rm", "--no-deps", "assessment-migrate")),
                        steps.index(("up", "-d", "--no-build", "--wait", "--wait-timeout", "150",
                                     "valkey", "external-sim", "assessment")))
        self.assertEqual(opened.credit_environment(receipt), {"ASSESSMENT_CREDIT_KAFKA_ENABLED": "true"})
        with patch.object(assessment, "run", return_value="CABINET_ASSESSMENT_KAFKA_ENABLED=false"):
            with self.assertRaisesRegex(RuntimeError, "другой режим"):
                opened.verify_credit(receipt)
        environment = "\n".join((
            "CABINET_ASSESSMENT_KAFKA_ENABLED=true",
            "CABINET_ASSESSMENT_KAFKA_BOOTSTRAP_SERVERS=assessment-kafka:9092",
            "CABINET_ASSESSMENT_KAFKA_ENVIRONMENT=dev",
            f"CABINET_ASSESSMENT_KAFKA_GENERATION={opened.generation}",
        ))
        with patch.object(assessment, "run", return_value=environment):
            opened.verify_credit(receipt)
            opened.verify_credit(receipt)
        self.assertEqual(assessment.read_record(opened.state / "active.json"), receipt)
        self.assertEqual(assessment.read_record(opened.receipt_path(receipt)), receipt)

    def test_rollout_refuses_active_credit_or_replaced_volumes(self):
        opened = self.open()
        self.deployment.compose.return_value = "still-running"
        with self.assertRaisesRegex(RuntimeError, "закройте вход"):
            opened.prepare()
        self.deployment.sql.assert_not_called()
        self.deployment.compose.return_value = ""
        assessment.write_record(opened.state / "binding.json", {"old": True})
        with (patch.object(assessment, "verify_image", return_value=IMAGE_ID),
              patch.object(assessment.AssessmentDev, "volume_binding", return_value={"new": True})):
            with self.assertRaisesRegex(RuntimeError, "отличаются"):
                opened.prepare()
        self.assertFalse(any(call.args[0] == "up" for call in self.deployment.compose.call_args_list))

    def test_rollback_checks_database_backlog_and_group_offsets(self):
        opened = self.open()
        self.deployment.sql.return_value = "1"
        with self.assertRaisesRegex(RuntimeError, "открытые циклы или outbox"):
            opened.require_drained()
        self.deployment.sql.return_value = "0"
        with (patch.object(assessment.AssessmentDev, "database", return_value="0"),
              patch.object(assessment.AssessmentDev, "offsets", return_value=3),
              patch.object(assessment.AssessmentDev, "group_offset", return_value=2) as group):
            with self.assertRaisesRegex(RuntimeError, "завершает чтение"):
                opened.require_drained()
            group.return_value = 3
            self.assertEqual(opened.require_drained(), {"commands": 3, "results": 3})

    def test_existing_topic_cannot_be_recreated_or_compacted(self):
        opened = self.open()
        cluster = f"ClusterId: {opened.secrets['ASSESSMENT_CLUSTER_ID']}"
        config = ("PartitionCount: 1 ReplicationFactor: 1 Configs: cleanup.policy=delete,compact,"
                  "retention.ms=-1,retention.bytes=-1\nLeader: 1")
        with patch.object(assessment.AssessmentDev, "kafka", side_effect=[cluster, "\n".join(opened.topics.values()), config]):
            with self.assertRaisesRegex(RuntimeError, "конфигурация topic"):
                opened.ensure_topics(existing=True)
        with patch.object(assessment.AssessmentDev, "kafka", side_effect=[cluster, ""]) as broker:
            with self.assertRaisesRegex(RuntimeError, "ранее созданный topic"):
                opened.ensure_topics(existing=True)
        self.assertEqual(broker.call_count, 2)

    def test_restore_requires_an_isolated_project_and_exact_checkpoint(self):
        with self.assertRaisesRegex(ValueError, "отдельный проект"):
            self.open().verify_restore({})
        self.deployment.project = "cabinet-dev-restore-check"
        restored = self.open()
        checkpoint = {"generation": restored.generation, "kafka": {"commands": {"end": 7}}}
        with patch.object(assessment.AssessmentDev, "backup_checkpoint", return_value=checkpoint):
            with self.assertRaisesRegex(RuntimeError, "сохранённой точки"):
                restored.verify_restore({"kafka": {"commands": {"end": 6}}})
            restored.verify_restore(checkpoint)
        self.assertEqual(assessment.read_record(restored.state / "restore-verified.json"), checkpoint)

    def test_local_rollout_does_not_write_a_receipt_while_remote_work_remains(self):
        self.deployment.release = release("local")
        opened = self.open()
        binding = {"volumes": "original"}
        assessment.write_record(opened.state / "binding.json", binding)
        assessment.write_record(opened.state / "active.json", {"mode": "remote", "offsets": {"commands": 2, "results": 1}})
        with (patch.object(assessment, "verify_image", return_value=IMAGE_ID),
              patch.object(assessment.AssessmentDev, "database", side_effect=["", opened.generation, "", "1"]),
              patch.object(assessment.AssessmentDev, "ensure_topics"),
              patch.object(assessment.AssessmentDev, "volume_binding", return_value=binding),
              patch.object(assessment.AssessmentDev, "verify_containers"),
              patch.object(assessment.AssessmentDev, "offsets", side_effect=lambda name, time="-1": 0 if time == "-2" else 3),
              patch.object(assessment.AssessmentDev, "require_drained", side_effect=RuntimeError("backlog")) as drained):
            with self.assertRaisesRegex(RuntimeError, "backlog"):
                opened.prepare()
        drained.assert_called_once()
        self.assertFalse((opened.state / "pending.json").exists())
        self.assertEqual(assessment.read_record(opened.state / "active.json")["mode"], "remote")

    def test_backup_checkpoint_preserves_uncommitted_offsets_and_both_database_counters(self):
        opened = self.open()
        self.deployment.sql.return_value = '{"cycles": 2}'
        with (patch.object(assessment.AssessmentDev, "ensure_topics"),
              patch.object(assessment.AssessmentDev, "database", side_effect=[opened.generation, '{"executions":1}']),
              patch.object(assessment.AssessmentDev, "offsets",
                           side_effect=lambda name, time="-1": 0 if time == "-2" else (3 if name == "commands" else 1)),
              patch.object(assessment.AssessmentDev, "group_offset", side_effect=[2, None])):
            checkpoint = opened.backup_checkpoint()
        self.assertEqual(checkpoint["assessment"], {"executions": 1})
        self.assertEqual(checkpoint["credit"], {"cycles": 2})
        self.assertEqual(checkpoint["kafka"]["commands"]["committed"], 2)
        self.assertEqual(checkpoint["kafka"]["commands"]["end"], 3)
        self.assertIsNone(checkpoint["kafka"]["results"]["committed"])


class ManifestsTest(unittest.TestCase):
    def test_compose_roles_and_four_private_networks(self):
        compose = yaml.safe_load(assessment.OVERLAY.read_text())
        services = compose["services"]
        self.assertTrue(all(spec == {"internal": True} for spec in compose["networks"].values()))
        self.assertEqual(set(services["assessment"]["networks"]), set(compose["networks"]))
        for name, peer in (("credit", "assessment-stream"), ("valkey", "assessment-cache"),
                           ("external-sim", "assessment-sources")):
            self.assertEqual(services[name]["networks"], [peer])
        for component in assessment.SERVICES:
            self.assertNotIn("ports", services[component])
            self.assertEqual(services[component]["pull_policy"], "never")
        runtime = services["assessment"]["environment"]
        self.assertEqual(runtime["ASSESSMENT_DB_USER"], "assessment_app")
        self.assertEqual(runtime["SPRING_FLYWAY_ENABLED"], "false")
        self.assertNotIn("MIGRATION_PASSWORD", "".join(runtime.values()))

    @unittest.skipUnless(shutil.which("helm"), "Helm is required for rendering")
    def test_helm_requires_release_and_readiness_for_remote(self):
        base = ["helm", "template", "cabinet", str(ROOT / "helm/cabinet"), "--namespace", "cabinet-dev",
                "--is-upgrade", "-f", str(ROOT / "helm/cabinet/values.assessment.yaml"),
                "--set-string", f"release.revision={REVISION}", "--set-string", "app.publicURL=https://example.invalid",
                "--set-string", "s3.endpoint=https://s3.example.invalid", "--set-string", "gateway.dnsResolver=10.96.0.10"]
        for name in ("identity", "credit", "engagement", "external-sim", "frontend", "swagger",
                     "postgres", "assessment", "assessment-kafka"):
            artifact = "kafka" if name == "assessment-kafka" else name
            base += ["--set-string", f"images.{name}={image(artifact)}"]
        enabled = ["--set", "assessment.enabled=true", "--set-string", "assessment.generation=g0123456789abcdef",
                   "--set-string", "assessment.clusterId=AAAAAAAAAAAAAAAAAAAAAA"]

        def render(*arguments):
            result = subprocess.run([*base, *arguments], capture_output=True, text=True, timeout=15)
            self.assertEqual(result.returncode, 0, result.stderr)
            return {(doc["kind"], doc["metadata"]["name"]): doc for doc in yaml.safe_load_all(result.stdout) if doc}

        self.assertNotIn(("Deployment", "assessment"), render())
        config = ("ConfigMap", "assessment-credit-transport")
        self.assertEqual(render(*enabled)[config]["data"]["CABINET_ASSESSMENT_KAFKA_ENABLED"], "false")
        denied = subprocess.run(base + enabled + ["--set-string", "assessment.creditMode=remote"],
                                capture_output=True, text=True, timeout=15)
        self.assertNotEqual(denied.returncode, 0)
        self.assertIn("verified readiness receipt", denied.stderr)
        remote = render(*enabled, "--set-string", "assessment.creditMode=remote",
                        "--set-string", f"assessment.receipt.revision={REVISION}",
                        "--set-string", "assessment.receipt.generation=g0123456789abcdef",
                        "--set-string", f"assessment.receipt.sha256={MANIFEST}")
        self.assertEqual(remote[config]["data"]["CABINET_ASSESSMENT_KAFKA_ENABLED"], "true")
        self.assertEqual(remote["StatefulSet", "assessment-kafka"]["spec"]["persistentVolumeClaimRetentionPolicy"],
                         {"whenDeleted": "Retain", "whenScaled": "Retain"})
        self.assertNotIn(("Service", "assessment"), remote)

    def test_shell_cache_guard_requires_auth_and_pong(self):
        password = "p" * 40
        auth = f"*2\r\n$4\r\nAUTH\r\n$40\r\n{password}\r\n".encode()
        ping = b"*1\r\n$4\r\nPING\r\n"
        for accepted in (True, False):
            with self.subTest(accepted=accepted), socket.socket() as listener:
                listener.bind(("127.0.0.1", 0))
                listener.listen(1)
                listener.settimeout(6)
                received = []

                def serve():
                    with listener.accept()[0] as connection, connection.makefile("rb") as wire:
                        connection.settimeout(5)
                        received.append(wire.read(len(auth)))
                        connection.sendall(b"+OK\r\n" if accepted else b"-WRONGPASS\r\n")
                        if accepted:
                            received.append(wire.read(len(ping)))
                            connection.sendall(b"+PONG\r\n")

                server = Thread(target=serve, daemon=True)
                server.start()
                env = os.environ | {"CABINET_VALKEY_HOST": "127.0.0.1", "CABINET_VALKEY_ENABLED": "true",
                                    "CABINET_VALKEY_PORT": str(listener.getsockname()[1]), "CABINET_VALKEY_PASSWORD": password}
                command = ["bash", assessment.FILES / "launch.sh", "cache"]
                result = subprocess.run(command, env=env, capture_output=True, timeout=8)
                server.join(6)
                self.assertEqual(result.returncode == 0, accepted)
                self.assertEqual(received, [auth, ping] if accepted else [auth])
                self.assertNotIn(password.encode(), result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
