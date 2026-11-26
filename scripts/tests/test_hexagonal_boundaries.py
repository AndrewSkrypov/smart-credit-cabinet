import importlib.util
from pathlib import Path
import shutil
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "check-hexagonal-boundaries.py"
spec = importlib.util.spec_from_file_location("hexagonal_boundaries", SCRIPT)
boundaries = importlib.util.module_from_spec(spec)
spec.loader.exec_module(boundaries)


class HexagonalBoundariesTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        for name in boundaries.MODULES:
            original = boundaries.ROOT / "libs" / name
            target = self.root / "libs" / name
            shutil.copytree(original / "src/main/kotlin", target / "src/main/kotlin")
            shutil.copyfile(original / "build.gradle.kts", target / "build.gradle.kts")

    def test_extracted_cores_pass(self):
        self.assertEqual([], boundaries.check(self.root)["errors"])

    def test_transport_reference_without_import_fails(self):
        source = next((self.root / "libs/loan-math/src/main/kotlin").rglob("*.kt"))
        source.write_text(source.read_text() + "\nval transport = io.grpc.Status.OK\n")
        self.assertTrue(boundaries.check(self.root)["errors"])

    def test_multiline_dependency_on_application_fails(self):
        build = self.root / "libs/loan-math/build.gradle.kts"
        build.write_text(build.read_text() + '\ndependencies {\n implementation(\n project(":services:credit")\n )\n}\n')
        self.assertTrue(boundaries.check(self.root)["errors"])

    def test_multiline_framework_dependency_fails(self):
        build = self.root / "libs/loan-math/build.gradle.kts"
        build.write_text(build.read_text() + '\ndependencies {\n api(\n "org.springframework:spring-core:6.2.15"\n )\n}\n')
        self.assertTrue(boundaries.check(self.root)["errors"])
