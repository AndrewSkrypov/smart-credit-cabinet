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
        for name in boundaries.LEGACY_SERVICES:
            self.write_legacy(name, "ru.creditcabinet.fixture.application", "class Example")

    def write_legacy(self, service, package, text):
        file = self.root / "services" / service / "src/main/kotlin" / package.replace(".", "/") / "Fixture.kt"
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(f"package {package}\n\n{text}\n")

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

    def test_known_legacy_dependencies_fail_in_their_actual_layers(self):
        self.write_legacy("identity", "ru.creditcabinet.auth.application",
                          "import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate\n"
                          "class ConsentLog(val jdbc: NamedParameterJdbcTemplate)")
        self.write_legacy("credit", "ru.creditcabinet.financing.scenario.application",
                          "import org.springframework.web.multipart.MultipartFile as Upload\nclass Submit(val file: Upload)")
        self.write_legacy("engagement", "ru.creditcabinet.assistant.application",
                          "class Advice(val data: ru.creditcabinet.assistant.infrastructure.CreditApplications)")
        self.write_legacy("engagement", "ru.creditcabinet.assistant.domain",
                          "data class Facts(val data: com.fasterxml.jackson.databind.JsonNode)")
        errors = boundaries.check(self.root)["errors"]
        for dependency in ("NamedParameterJdbcTemplate", "MultipartFile", "CreditApplications", "JsonNode"):
            self.assertTrue(any(dependency in error for error in errors), errors)

    def test_domain_can_depend_on_other_domain_and_application_on_ports(self):
        self.write_legacy("credit", "ru.creditcabinet.application.domain", "class Application")
        self.write_legacy("credit", "ru.creditcabinet.financing.scenario.domain",
                          "import ru.creditcabinet.application.domain.Application\nclass Plan(val application: Application)")
        self.write_legacy("credit", "ru.creditcabinet.financing.scenario.application",
                          "import ru.creditcabinet.financing.scenario.domain.Plan\nclass PlanUseCase(val plan: Plan)")
        self.assertEqual([], boundaries.check(self.root)["errors"])

    def test_application_allows_existing_spring_metadata_but_rejects_runtime_services(self):
        self.write_legacy("credit", "ru.creditcabinet.application.service",
                          "import org.springframework.stereotype.Service\n"
                          "import org.springframework.transaction.annotation.Transactional\n"
                          "import org.springframework.context.ApplicationContext\n"
                          "@Service class Submit(val context: ApplicationContext)\n"
                          "@Transactional fun submit() = Unit")
        errors = boundaries.check(self.root)["errors"]
        self.assertEqual(1, len(errors), errors)
        self.assertIn("org.springframework.context.ApplicationContext", errors[0])

    def test_compiled_check_rejects_jdbc_controller_and_same_context_adapter(self):
        output = """
main -> not found
   ru.creditcabinet.auth.application.ConsentLog          -> java.sql.Connection                         java.sql
   ru.creditcabinet.assistant.application.UseCase$1       -> ru.creditcabinet.assistant.infrastructure.HttpReader  main
   ru.creditcabinet.financing.scenario.domain.Rules       -> ru.creditcabinet.financing.scenario.application.Read    not found
   ru.creditcabinet.auth.controller.Controller            -> jakarta.persistence.EntityManager                   not found
   ru.creditcabinet.auth.infrastructure.JdbcConsent      -> org.springframework.jdbc.core.JdbcTemplate           not found
   ru.creditcabinet.application.domain.Draft              -> ru.creditcabinet.product.domain.Product               main
"""
        errors = boundaries.bytecode_errors(output)
        self.assertEqual(4, len(errors))
        self.assertTrue(any("ConsentLog" in error and "java.sql.Connection" in error for error in errors))
        self.assertTrue(any("UseCase$1" in error and "HttpReader" in error for error in errors))
        self.assertTrue(any("scenario.domain.Rules" in error and "scenario.application.Read" in error for error in errors))
        self.assertTrue(any("Controller" in error and "EntityManager" in error for error in errors))
