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

    def test_url_literal_does_not_hide_following_reference(self):
        source = next((self.root / "libs/loan-math/src/main/kotlin").rglob("*.kt"))
        source.write_text(source.read_text() + '\nval url="https://example.invalid"; val transport=io.grpc.Status.OK\n')
        self.assertTrue(boundaries.check(self.root)["errors"])

    def test_spaced_and_commented_qualification_fails(self):
        source = next((self.root / "libs/loan-math/src/main/kotlin").rglob("*.kt"))
        source.write_text(source.read_text() + '\nval transport: org /* comment */ .springframework .context.ApplicationContext? = null\n')
        self.assertTrue(boundaries.check(self.root)["errors"])

    def test_comments_do_not_create_false_references(self):
        source = next((self.root / "libs/loan-math/src/main/kotlin").rglob("*.kt"))
        source.write_text(source.read_text() + '\n/* org.springframework .context /* nested */ .ApplicationContext */\n// io.grpc.Status.OK\n')
        self.assertEqual([], boundaries.check(self.root)["errors"])

    def test_string_interpolation_is_checked(self):
        source = next((self.root / "libs/loan-math/src/main/kotlin").rglob("*.kt"))
        source.write_text(source.read_text() + '\nval transport="${io.grpc.Status.OK}"\n')
        self.assertTrue(boundaries.check(self.root)["errors"])

    def test_nested_string_inside_interpolation_keeps_following_code_visible(self):
        source = next((self.root / "libs/loan-math/src/main/kotlin").rglob("*.kt"))
        source.write_text(source.read_text() + '\nval transport="${"https://example.invalid"} ${io /* nested /* comment */ */ .grpc.Status.OK}"\n')
        self.assertTrue(boundaries.check(self.root)["errors"])
