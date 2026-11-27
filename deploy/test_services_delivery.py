"""Exercise the integrated manifest, backup boundary and closed-ingress rollout."""
from contextlib import nullcontext
from copy import deepcopy
import json
from pathlib import Path
import runpy
import sys
import tarfile
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch

from backup_support import DATABASES, backup_archives, digest
from registry_release import IMAGE_COMPONENTS, RUNTIME_COMPONENTS, RegistryRelease
from runtime_attempts import authorize_servicing, begin, load, require_servicing_authorization
from runtime_backup import SERVICING_STATE, archive_state, check_alpha_continuity, projection_matches, unpack_state
from runtime_delivery_support import write_record

ROOT = Path(__file__).resolve().parents[1]
REVISION = "1" * 40
BEFORE = "2" * 40
HASH = "c" * 64
OTHER = "d" * 64
POSTGRES = "registry.example.invalid/team/cabinet"


def image(name, value=HASH):
    return f"{POSTGRES}/{name}@sha256:{value}"


def unified_manifest():
    images = {name: image(name) for name in (*IMAGE_COMPONENTS, *RUNTIME_COMPONENTS)}
    oci = {name: "sha256:" + OTHER for name in ("kafka", *RUNTIME_COMPONENTS)}
    manifest = {"revision": REVISION, "images": images, "runtime_oci": oci}
    for component, key in RUNTIME_COMPONENTS.items():
        record = {"dev_mode": "disabled", "jar_sha256": HASH}
        if component == "catalog-grpc":
            record["config_digest"] = oci[component]
        if component == "assessment":
            record["kafka_image"] = image("kafka")
        manifest[key] = record
    return manifest


def volume_checkpoint():
    return {
        "volumes": {
            "alpha-analytics-data": {"name": "db-volume", "created_at": "1"},
            "alpha-kafka-data": {"name": "broker-volume", "created_at": "1"},
        },
        "checkpoint": {
            "generation": "g1234567890abcdef", "marker": "topic " + HASH,
            "topics": {"topic": {"id": "kafka-id", "begin": {"0": 0}, "end": {"0": 4}}},
            "committed": {"0": 3},
            "outbox": {"cutoff": "2026-01-01T00:00:00.000000Z", "rows": 2, "sha256": HASH, "open": 1},
        },
    }


class DeliveryTest(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def read_release(self, content):
        path = self.root / "release.json"
        path.write_text(json.dumps(content))
        return RegistryRelease(path)

    def test_assessment_uses_release_kafka_without_alpha_and_checks_oci(self):
        content = unified_manifest()
        content["assessment"]["dev_mode"] = "remote"
        release = self.read_release(content)
        self.assertIsNone(release.alpha_dev)
        self.assertEqual(release.kafka_image, image("kafka"))
        with patch("registry_release.subprocess.run") as docker:
            docker.return_value.returncode = 0
            docker.return_value.stdout = f"sha256:{OTHER} {REVISION} {release.kafka_upstream}"
            release.pull_and_verify(["assessment-kafka"])
            self.assertEqual(docker.call_args_list[0].args[0], ["docker", "pull", image("kafka")])
            docker.return_value.stdout = f"sha256:{HASH} {REVISION} {release.kafka_upstream}"
            with self.assertRaisesRegex(RuntimeError, "OCI-конфигурации"):
                release.local_image_id("kafka")

    def test_manifest_rejects_partial_runtime_and_different_kafka(self):
        for malformed in ("servicing", "digest", "kafka", "repository"):
            with self.subTest(malformed=malformed):
                content = unified_manifest()
                if malformed == "servicing":
                    del content["servicing"]
                elif malformed == "digest":
                    del content["runtime_oci"]["kafka"]
                elif malformed == "kafka":
                    content["alpha_dev"] = {"image": image("analytics"), "jar_sha256": HASH, "kafka_image": image("kafka", OTHER)}
                else:
                    content["images"]["documents"] = image("documents").replace("/team/", "/other/")
                with self.assertRaises(ValueError):
                    self.read_release(content)

    def test_legacy_documents_image_remains_optional_and_unbound(self):
        release = self.read_release({"revision": REVISION, "images":
                                     {name: image(name) for name in (*IMAGE_COMPONENTS, "documents")}})
        self.assertFalse(release.has_all_runtimes)
        self.assertIsNone(release.documents)

    def test_servicing_restore_separates_receipt_for_source_database(self):
        source = self.root / "source"
        target = self.root / "target"
        backup = self.root / "servicing-state.tar.gz"
        receipt = f"ready/{HASH}-initial-local.json"
        for name in ("authority.json", receipt):
            path = source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("{}")
        archive_state(source, backup, SERVICING_STATE, {"authority.json"})
        unpack_state(target, backup, SERVICING_STATE, {"authority.json"})
        self.assertEqual((target / f"source-{receipt}").read_text(), "{}")
        self.assertFalse((target / receipt).exists())

    def test_backup_without_all_expected_runtime_files_is_rejected(self):
        base = {"format_version": 4, "backup_complete": True, "release_manifest_sha256": HASH,
                "storage": {"s3_volume_included": False, "backends": {}},
                "databases": {name: {} for name in DATABASES}, "documents": {},
                "files": {name: {} for name in ("credit.dump", "identity.dump", "engagement.dump", "documents.tar.gz")}}
        with self.assertRaisesRegex(ValueError, "состав runtime"):
            backup_archives({**base, "runtimes": {"unknown": "remote"}})
        with self.assertRaisesRegex(AssertionError, "состав копии"):
            backup_archives({**base, "runtimes": {"assessment": "remote"}, "assessment_runtime": {}})

    def test_restore_with_credit_s3_ledger_requires_storage_when_documents_is_disabled(self):
        for version in (2, 4):
            with self.subTest(version=version):
                directory = self.root / str(version)
                directory.mkdir()
                env = directory / "cabinet.env"
                env.touch(mode=0o600)
                names = {db + ".dump" for db in DATABASES} | {"documents.tar.gz", "seaweedfs.tar.gz"}
                archive = {"format_version": version, "backup_complete": True, "documents": {}, "source_project": "cabinet-dev",
                           "databases": {db: {} for db in DATABASES}, "release": REVISION, "storage": {
                               "s3_volume_included": True, "backends": {"LOCAL": 1}, "requested_backends": ["S3"],
                               "s3_bucket": "credit", "seaweedfs_files": {}}}
                project = "cabinet-dev-restore-ledger" if version == 4 else "cabinet-prod-restore"
                args = ["restore.py", str(directory), "--env-file", str(env), "--project", project]
                if version == 4:
                    content = unified_manifest()
                    content["assessment"]["dev_mode"] = "local"
                    release = self.read_release(content)
                    args.extend(("--release-manifest", str(release.path)))
                    archive.update(release_manifest_sha256=release.checksum, runtimes={"assessment": "local"}, assessment_runtime={})
                    names.update(("assessment.dump", "assessment-kafka.tar.gz", "assessment-state.tar.gz"))
                archive["files"] = {}
                for name in names:
                    path = directory / name
                    if name in {"documents.tar.gz", "seaweedfs.tar.gz"}:
                        with tarfile.open(path, "w:gz"):
                            pass
                    else:
                        path.write_bytes(b"fixture")
                    archive["files"][name] = {"bytes": path.stat().st_size, "sha256": digest(path)}
                write_record(directory / "manifest.json", archive)
                deployment = MagicMock(profiles={"prod"}, documents=None, runtime_modes={}, values={"CABINET_RELEASE": REVISION})
                with (patch.object(sys, "argv", args), patch("common.deploy_lock", return_value=nullcontext()),
                      patch("common.Deployment", return_value=deployment), patch("runtime_backup.stage_private") as stage,
                      patch("backup_support.checked_docker") as docker):
                    with self.assertRaisesRegex(RuntimeError, "Для восстановления S3 включите storage"):
                        runpy.run_path(str(ROOT / "deploy/restore.py"), run_name="__main__")
                stage.assert_not_called()
                docker.assert_not_called()
                deployment.compose.assert_not_called()

    def test_servicing_switch_is_bound_to_verified_backup_and_attempt(self):
        directory = self.root / "backups" / "fresh"
        directory.mkdir(mode=0o700, parents=True)
        files = {}
        for name in ("identity.dump", "credit.dump", "engagement.dump", "documents.tar.gz", "servicing.dump", "servicing-state.tar.gz"):
            target = directory / name
            target.write_bytes(b"simulated " + name.encode())
            files[name] = {"bytes": target.stat().st_size, "sha256": digest(target)}
        archive = {
            "format_version": 4, "source_project": "cabinet-dev", "release": BEFORE, "release_manifest_sha256": OTHER,
            "backup_complete": True, "source_resumed": True, "databases": {db: {} for db in DATABASES}, "documents": {},
            "storage": {"s3_volume_included": False, "backends": {}}, "runtimes": {"servicing": "local"},
            "servicing_runtime": {"mode": "local"}, "files": files,
        }
        write_record(directory / "manifest.json", archive)
        before = {"revision": BEFORE, "manifest_sha256": OTHER, "servicing": {"mode": "local"}}
        after = {"revision": REVISION, "manifest_sha256": HASH, "servicing": {"mode": "remote"}}
        release = SimpleNamespace(revision=REVISION, checksum=HASH)
        record = begin(self.root, before, after, directory)
        self.assertEqual(record, load(self.root, release))
        deployment = SimpleNamespace(path=self.root / "cabinet.env", release=release,
                                     servicing=SimpleNamespace(descriptor=SimpleNamespace(mode="remote")))
        write_record(self.root / "pending.json", after)
        with self.assertRaisesRegex(RuntimeError, "неизменяемого разрешения"):
            require_servicing_authorization(deployment, "local")
        authorize_servicing(deployment, record)
        path = self.root / "runtime-attempts" / (record["attempt"] + "-servicing.json")
        first = path.read_bytes()
        self.assertEqual(json.loads(first)["backup_manifest_sha256"], digest(directory / "manifest.json"))
        authorize_servicing(deployment, record)
        self.assertEqual(path.read_bytes(), first)
        self.assertEqual(require_servicing_authorization(deployment, "local"), json.loads(first))
        (directory / "servicing.dump").write_text("altered")
        with self.assertRaisesRegex(RuntimeError, "servicing.dump"):
            require_servicing_authorization(deployment, "local")

    def test_alpha_checkpoint_accepts_growth_and_rejects_loss_or_replacement(self):
        before = volume_checkpoint()
        after = deepcopy(before)
        after["checkpoint"]["topics"]["topic"]["end"]["0"] = 7
        after["checkpoint"]["committed"]["0"] = 7
        after["checkpoint"]["outbox"]["open"] = 0
        check_alpha_continuity(before, after)
        for change in ("volume", "snapshot", "commit", "end", "outbox"):
            with self.subTest(change=change):
                wrong = deepcopy(after)
                if change == "volume":
                    wrong["volumes"]["alpha-kafka-data"]["created_at"] = "2"
                elif change == "snapshot":
                    wrong["checkpoint"]["marker"] = "different"
                elif change == "commit":
                    wrong["checkpoint"]["committed"]["0"] = None
                elif change == "end":
                    wrong["checkpoint"]["topics"]["topic"]["end"]["0"] = 3
                elif change == "outbox":
                    wrong["checkpoint"]["outbox"]["sha256"] = OTHER
                with self.assertRaises(RuntimeError):
                    check_alpha_continuity(before, wrong)

    def test_alpha_projection_uses_status_event_versions_not_versions_of_draft_edits(self):
        def rows(*records):
            return "\n".join(map(json.dumps, records))

        credit = rows(("snapshot-draft", 5, "DRAFT", None, None), ("recent-draft", 2, "DRAFT", None, None),
                      ("submitted", 8, "SUBMITTED", 6, "SUBMITTED"))
        snapshot_draft = ("snapshot-draft", 1, "DRAFT")
        submitted = ("submitted", 6, "SUBMITTED")
        self.assertTrue(projection_matches(credit, rows(snapshot_draft, submitted)))
        self.assertFalse(projection_matches(credit, rows(snapshot_draft)))
        self.assertFalse(projection_matches(credit, rows(snapshot_draft, ("submitted", 5, "SUBMITTED"))))
        self.assertFalse(projection_matches(credit, rows(snapshot_draft, ("submitted", 6, "APPROVED"))))
        self.assertFalse(projection_matches(credit, rows(snapshot_draft, submitted, ("unknown", 1, "DRAFT"))))

    def test_same_alpha_attempt_resumes_after_each_cold_store_start_failure(self):
        stores = {"postgres": "cabinet-postgres-data", "analytics-db": "alpha-analytics-data",
                  "analytics-kafka": "alpha-kafka-data"}
        before = volume_checkpoint()
        volumes = {**before["volumes"], "cabinet-postgres-data": {"name": "credit-volume", "created_at": "1"}}
        old = {service: {"reference": image("kafka" if service.endswith("kafka") else "postgres"),
                         "image_id": "sha256:" + HASH} for service in stores}
        target = {service: {"reference": image("kafka" if service.endswith("kafka") else "postgres", OTHER),
                            "image_id": "sha256:" + OTHER} for service in stores}
        args = ["start.py", "--project", "cabinet-dev", "--release-manifest", "release.json", "--no-build"]

        for failure in stores:
            with self.subTest(failure=failure):
                root = self.root / failure
                journal = root / "runtime-attempts"
                journal.mkdir(parents=True)
                identifier = "a" * 32
                pending = {"revision": REVISION, "manifest_sha256": HASH}
                attempt = {"attempt": identifier, "previous": {"revision": BEFORE, "manifest_sha256": OTHER},
                           "target": pending, "alpha": {"frozen": before, "preflight": {"source_images": old,
                                      "credit_volume": volumes["cabinet-postgres-data"]}}}
                write_record(journal / (identifier + ".json"), attempt)
                write_record(root / "runtime-attempt.json", {"attempt": identifier})
                write_record(root / "pending.json", pending)
                phase_path = journal / (identifier + "-alpha-stores.json")
                running = set(stores)
                images = dict(old)
                latest = deepcopy(before["checkpoint"])
                calls = []
                fail_once = True
                deployment = MagicMock()
                deployment.project = "cabinet-dev"
                deployment.path = root / "cabinet.env"
                deployment.gateway_config = root / "gateway.conf"
                deployment.catalog_enabled = deployment.ollama_ssh = deployment.isolated_demo = False
                deployment.catalog_state = root / "catalog"
                deployment.documents = None
                deployment.alpha_dev = SimpleNamespace()
                deployment.runtime_modes = {"servicing": "local"}
                deployment.release = SimpleNamespace(has_all_runtimes=True, revision=REVISION, checksum=HASH,
                    pull_and_verify=MagicMock(), image_for=lambda component: image(component, OTHER),
                    local_image_id=lambda _: "sha256:" + OTHER)
                deployment.active_services.return_value = {name: {"mem_limit": "1024"}
                    for name in (*stores, "gateway", "credit", "caddy", "analytics")}

                def compose(*command, **_):
                    nonlocal fail_once
                    calls.append(command)
                    service = command[-1]
                    if command[:3] == ("ps", "-a", "-q"):
                        return service if service in images else ""
                    if command[0] == "up" and service in stores and "caddy" not in command:
                        self.assertTrue(phase_path.exists())
                        if service == failure and fail_once:
                            fail_once = False
                            # Interruption can remove the failed container and halt another planned one.
                            running.discard(service)
                            images.pop(service)
                            running.discard("analytics-kafka" if service != "analytics-kafka" else "postgres")
                            latest["topics"]["topic"]["end"]["0"] = 8
                            latest["committed"]["0"] = 8
                            raise RuntimeError("injected storage interruption")
                        images[service] = target[service]
                        running.add(service)
                    return ""

                def container(service):
                    self.assertIn(service, running, f"running lookup for cold {service}")
                    return service

                def mount(_, service, __, ___, identifier):
                    self.assertEqual(identifier, service)
                    actual = images[service]
                    return {"Config": {"Image": actual["reference"]}, "Image": actual["image_id"]}

                def read_checkpoint(*_):
                    self.assertEqual(running, set(stores))
                    self.assertEqual(latest["committed"]["0"], 8)
                    return latest

                deployment.compose.side_effect = compose
                deployment.container.side_effect = container
                with (patch.object(sys, "argv", args), patch("common.Deployment", return_value=deployment),
                      patch("common.deploy_lock", return_value=nullcontext()), patch("subprocess.run"),
                      patch("runtime_backup.project_volume", side_effect=lambda _, key: volumes[key]["name"]),
                      patch("runtime_backup.guard_volume", return_value={"CreatedAt": "1"}),
                      patch("runtime_backup.guard_mount", side_effect=mount),
                      patch("runtime_backup.alpha_checkpoint", side_effect=read_checkpoint),
                      patch("alpha_dev.prepare", return_value=False) as alpha_prepare,
                      patch("runtime_rollout.prepare_private"), patch("runtime_rollout.verify_private"),
                      patch("runtime_backup.verify_alpha_catchup", side_effect=lambda *_: {"caught_up": True,
                          "checkpoint": read_checkpoint()})):
                    images["postgres"] = target["postgres"]
                    with self.assertRaisesRegex(RuntimeError, "образами подтверждённой фазы"):
                        runpy.run_path(str(ROOT / "deploy/start.py"), run_name="__main__")
                    self.assertFalse(phase_path.exists())
                    images["postgres"] = old["postgres"]
                    with self.assertRaisesRegex(RuntimeError, "injected storage interruption"):
                        runpy.run_path(str(ROOT / "deploy/start.py"), run_name="__main__")
                    phase = phase_path.read_bytes()
                    self.assertNotIn(("up", "-d", "--no-deps", "caddy"), calls)
                    alpha_prepare.assert_not_called()
                    kafka_image = images.get("analytics-kafka")
                    images["analytics-kafka"] = {"reference": image("kafka", "e" * 64), "image_id": "sha256:" + "e" * 64}
                    with self.assertRaisesRegex(RuntimeError, "образами подтверждённой фазы"):
                        runpy.run_path(str(ROOT / "deploy/start.py"), run_name="__main__")
                    if kafka_image is None:
                        images.pop("analytics-kafka")
                    else:
                        images["analytics-kafka"] = kafka_image
                    runpy.run_path(str(ROOT / "deploy/start.py"), run_name="__main__")
                self.assertEqual(phase_path.read_bytes(), phase)
                self.assertEqual(calls[-1], ("up", "-d", "--no-deps", "caddy"))
                self.assertEqual(images, target)
                alpha_prepare.assert_called_once()
                ready = json.loads((journal / (identifier + "-alpha-ready.json")).read_bytes())
                self.assertEqual(ready["checkpoint"]["committed"]["0"], 8)

    def test_start_keeps_ingress_closed_when_last_hook_fails_after_prior_write_transfer(self):
        deployment = MagicMock()
        deployment.catalog_enabled = True
        deployment.catalog_mode = "remote"
        deployment.catalog_state = self.root / "catalog"
        deployment.runtime_modes = {key: "remote" for key in ("catalog", "assessment", "servicing", "documents")}
        deployment.environment = {"CABINET_CATALOG_MODE": "local", "CABINET_DOCUMENTS_MODE": "local"}
        deployment.release.has_all_runtimes = False
        deployment.alpha_dev = None
        deployment.ollama_ssh = False
        deployment.gateway_config = self.root / "gateway.conf"
        deployment.active_services.return_value = {name: {"mem_limit": "1024"}
                                                  for name in ("postgres", "gateway", "credit", "caddy")}
        deployment.assessment.credit_environment.return_value = {"ASSESSMENT_CREDIT_KAFKA_ENABLED": "true"}
        deployment.servicing.switch.return_value = {"CABINET_SERVICING_CREDIT_MODE": "remote"}
        deployment.documents.prepare.side_effect = RuntimeError("Documents prepare failed")
        args = ["start.py", "--project", "cabinet-dev", "--release-manifest", "release.json", "--no-build"]
        with (patch.object(sys, "argv", args), patch("common.Deployment", return_value=deployment),
              patch("common.deploy_lock", return_value=nullcontext()), patch("catalog.check_tls"),
              patch("runtime_rollout.transfer_catalog"), patch("subprocess.run"),
              self.assertRaisesRegex(RuntimeError, "Documents prepare failed")):
            runpy.run_path(str(ROOT / "deploy/start.py"), run_name="__main__")
        calls = [call.args for call in deployment.compose.call_args_list]
        self.assertEqual(calls[0], ("stop", "caddy"))
        self.assertIn(("run", "--rm", "--no-deps", "credit-migrate"), calls)
        self.assertTrue(all(not (args[0] == "up" and set(args) & {"credit", "caddy"}) for args in calls))
        self.assertEqual(deployment.environment["CABINET_DOCUMENTS_MODE"], "local")


if __name__ == "__main__":
    unittest.main()
