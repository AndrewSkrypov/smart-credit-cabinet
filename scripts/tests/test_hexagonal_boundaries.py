import importlib.util
from pathlib import Path
import shutil
import struct
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
        for name in (boundaries.TRANSPORT_LIBRARIES | boundaries.ADAPTER_LIBRARIES | {"platform-common"}):
            directory = Path("libs") / name / "src/main/kotlin"
            shutil.copytree(boundaries.ROOT / directory, self.root / directory)
        shutil.copytree(boundaries.ROOT / "contracts/proto", self.root / "contracts/proto")
        for name in boundaries.SERVICES:
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

    def test_credit_application_rejects_http_error_subclasses_and_aliases(self):
        self.write_legacy("credit", "ru.creditcabinet.fixture.application",
                          "import ru.creditcabinet.common.exception.NotFoundException\n"
                          "import ru.creditcabinet.common.limits.SharedLimitExceeded\n"
                          "import ru.creditcabinet.compat.ImportedFailure\n"
                          "class ReadApplication(val failure: ImportedFailure?) {\n"
                          "    fun load(): Nothing = throw NotFoundException(\"Нет заявки\")\n"
                          "    fun limit(): Nothing = throw SharedLimitExceeded(\"Лимит\", 5)\n"
                          "}")
        self.write_legacy("credit", "ru.creditcabinet.compat", "typealias ImportedFailure = ru.creditcabinet.fixture.domain.RemoteFailure")
        self.write_legacy("credit", "ru.creditcabinet.fixture.domain",
                          "import ru.creditcabinet.common.exception.ApiException as GatewayError\n"
                          "open class RemoteMissing(message: String = \"Не найдено (удалённо)\") : GatewayError(org.springframework.http.HttpStatus.NOT_FOUND, message)\n"
                          "class RemoteFailure : RemoteMissing()")
        catalog = boundaries.BoundaryCatalog(self.root)
        self.assertIn("ru.creditcabinet.fixture.domain.RemoteFailure", catalog.http_errors)
        self.assertIn("ru.creditcabinet.common.limits.SharedLimitExceeded", catalog.http_errors)
        errors = boundaries.check(self.root)["errors"]
        for name in ("NotFoundException", "SharedLimitExceeded", "ImportedFailure"):
            self.assertTrue(any("application depends" in error and name in error for error in errors), errors)
        bytecode = "\n".join(
            f"   ru.creditcabinet.fixture.application.ReadApplication -> {name} not found"
            for name in ("ru.creditcabinet.common.exception.NotFoundException", "ru.creditcabinet.common.limits.SharedLimitExceeded")
        )
        self.assertEqual(2, len(boundaries.bytecode_errors(bytecode, catalog)))

    def test_actual_documents_client_and_generated_dtos_are_transport(self):
        self.write_legacy("documents", "ru.creditcabinet.documents.domain",
                          "import ru.creditcabinet.documentsgrpc.DocumentsGrpcClient\n"
                          "import ru.creditcabinet.contracts.documents.v1.PutRequest\n"
                          "class ArtifactPolicy(private val client: DocumentsGrpcClient, private val request: PutRequest)")
        catalog = boundaries.BoundaryCatalog(self.root)
        errors = boundaries.check(self.root)["errors"]
        for name in ("ru.creditcabinet.documentsgrpc.DocumentsGrpcClient", "ru.creditcabinet.contracts.documents.v1.PutRequest"):
            self.assertTrue(any("domain depends" in error and name in error for error in errors), errors)
        output = """
   ru.creditcabinet.documents.domain.ArtifactPolicy -> ru.creditcabinet.documentsgrpc.DocumentsGrpcClient not found
   ru.creditcabinet.documents.domain.ArtifactPolicy -> ru.creditcabinet.contracts.documents.v1.PutRequest not found
   ru.creditcabinet.documents.application.Artifacts -> ru.creditcabinet.documents.ArtifactRecord not found
"""
        self.assertEqual(2, len(boundaries.bytecode_errors(output, catalog)))

    def test_documents_runtime_requires_a_production_layer(self):
        self.write_legacy("documents", "ru.creditcabinet.documents.storage", "class UnclassifiedWriter")
        self.write_legacy("documents", "ru.creditcabinet.documents.application",
                          "import ru.creditcabinet.documents.ArtifactRecord\nclass Artifacts(val record: ArtifactRecord)")
        errors = boundaries.check(self.root)["errors"]
        self.assertEqual(1, len(errors), errors)
        self.assertIn("unclassified production package ru.creditcabinet.documents.storage", errors[0])

    def test_pure_documents_use_cases_can_share_ports_but_domain_cannot_import_them(self):
        source = self.root / "libs/documents-model/src/main/kotlin/ru/creditcabinet/documents"
        application = source / "application"
        application.mkdir(exist_ok=True)
        (application / "ArtifactOperations.kt").write_text(
            "package ru.creditcabinet.documents.application\ninterface ArtifactOperations\n"
        )
        operations = application / "GenerateArtifact.kt"
        operations.write_text("package ru.creditcabinet.documents.application\n"
                              "class GenerateArtifact(val operations: ru.creditcabinet.documents.application.ArtifactOperations)\n")
        self.assertEqual([], boundaries.check(self.root)["errors"])
        domain = source / "domain"
        domain.mkdir(exist_ok=True)
        (domain / "ArtifactRule.kt").write_text(
            "package ru.creditcabinet.documents.domain\n"
            "import ru.creditcabinet.documents.application.ArtifactOperations\n"
            "import ru.creditcabinet.document.generated.ArtifactRenderer\n"
            "class ArtifactRule(val operations: ArtifactOperations, val renderer: ArtifactRenderer)\n"
        )
        errors = boundaries.check(self.root)["errors"]
        self.assertTrue(any("ArtifactOperations" in error for error in errors), errors)
        self.assertTrue(any("ArtifactRenderer" in error for error in errors), errors)

    def test_financing_controller_cannot_start_the_locking_transaction(self):
        target = Path("services/credit/src/main/kotlin/ru/creditcabinet/financing/scenario/controller/FinancingScenarioController.kt")
        controller = (boundaries.ROOT / target).read_text()
        write_call = "agents.saveFinancingScenario(id, inputs.toDomain()).toContract()"
        self.assertIn(write_call, controller)
        self.assertNotIn("import org.springframework.transaction", controller)
        prior = controller.replace("import org.springframework.http.ResponseEntity\n",
                                   "import org.springframework.http.ResponseEntity\n"
                                   "import org.springframework.transaction.annotation.Transactional\n")
        prior = prior.replace("    fun agentPut(", "    @Transactional\n    fun agentPut(")
        prior = prior.replace(write_call, "scenarios.saveDraft(agents.ownAgentApplication(id, forUpdate = true).second, inputs.toDomain()).toContract()")
        destination = self.root / target
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(prior)
        errors = boundaries.check(self.root)["errors"]
        self.assertTrue(any("transport depends on org.springframework.transaction.annotation.Transactional" in error for error in errors), errors)
        self.assertTrue(any("transport acquires a business write lock" in error for error in errors), errors)
        destination.write_text(controller)
        self.assertEqual([], boundaries.check(self.root)["errors"])

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

    def test_domain_and_application_in_the_other_services_are_checked(self):
        examples = (
            ("analytics", "analytics/domain/ApplicationStatus.kt", "org.springframework.jdbc.core.JdbcTemplate", "domain"),
            ("assessment", "assessmentservice/application/AssessmentExecution.kt",
             "ru.creditcabinet.contracts.catalog.v1.CatalogServiceGrpc.CatalogServiceBlockingStub", "application"),
            ("catalog-grpc", "catalog/application/CatalogUseCases.kt", "ru.creditcabinet.catalog.infrastructure.JdbcCatalogReader", "application"),
        )
        for service, relative, dependency, source_layer in examples:
            path = Path("services") / service / "src/main/kotlin/ru/creditcabinet" / relative
            destination = self.root / path
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text((boundaries.ROOT / path).read_text() + f"\nprivate val leak: {dependency}? = null\n")
        errors = boundaries.check(self.root)["errors"]
        for service, relative, dependency, source_layer in examples:
            with self.subTest(service=service):
                self.assertTrue(any(error.startswith(f"services/{service}/") and
                                    f"/{relative}: {source_layer} depends on {dependency}" in error for error in errors), errors)
        output = """
   ru.creditcabinet.analytics.domain.ApplicationStatus -> org.springframework.jdbc.core.JdbcTemplate not found
   ru.creditcabinet.assessmentservice.application.AssessmentExecution -> ru.creditcabinet.contracts.catalog.v1.CatalogServiceGrpc$CatalogServiceBlockingStub not found
   ru.creditcabinet.catalog.application.CatalogUseCases -> ru.creditcabinet.catalog.infrastructure.JdbcCatalogReader main
"""
        self.assertEqual(3, len(boundaries.bytecode_errors(output, boundaries.BoundaryCatalog(self.root))))

    def test_platform_common_domain_has_its_own_scope(self):
        domain = self.root / "libs/platform-common" / boundaries.COMMON_DOMAIN_PATH
        domain.mkdir(exist_ok=True, parents=True)
        (domain / "Failure.kt").write_text("package ru.creditcabinet.common.domain\n"
                                           "class DomainFailure(val status: org.springframework.http.HttpStatus)\n")
        report = boundaries.check(self.root)
        self.assertEqual(1, report["modules"]["platform-common"])
        self.assertEqual(1, len(report["errors"]), report)
        self.assertIn("domain depends on org.springframework.http.HttpStatus", report["errors"][0])
        output = """
   ru.creditcabinet.common.domain.DomainFailure -> org.springframework.http.HttpStatus not found
   ru.creditcabinet.common.exception.ApiException -> org.springframework.http.HttpStatus not found
"""
        errors = boundaries.bytecode_errors(output, boundaries.BoundaryCatalog(self.root), common_domain_only=True)
        self.assertEqual(1, len(errors), errors)
        self.assertIn("common.domain.DomainFailure", errors[0])

    def test_concrete_runtime_adapters_are_classified_with_no_fallback_for_new_files(self):
        for service in ("analytics", "assessment", "catalog-grpc", "external-sim", "servicing"):
            directory = Path("services") / service / "src/main/kotlin"
            shutil.copytree(boundaries.ROOT / directory, self.root / directory, dirs_exist_ok=True)
        self.assertEqual([], boundaries.check(self.root)["errors"])
        catalog = boundaries.BoundaryCatalog(self.root)
        self.assertEqual("transport", catalog.class_layer("ru.creditcabinet.cataloggrpc.CatalogGrpcService$WhenMappings"))
        self.assertEqual("composition", catalog.class_layer("ru.creditcabinet.cataloggrpc.CatalogGrpcConfigurationKt"))
        self.assertEqual("composition", catalog.class_layer("ru.creditcabinet.servicing.runtime.ServicingPeer"))
        self.assertEqual("transport", catalog.class_layer("ru.creditcabinet.sim.fns.FnsController"))
        self.write_legacy("catalog-grpc", "ru.creditcabinet.cataloggrpc", "class HiddenUseCase")
        self.write_legacy("new-service", "ru.creditcabinet.newservice.domain", "class Candidate")
        errors = boundaries.check(self.root)["errors"]
        self.assertEqual(2, len(errors), errors)
        self.assertTrue(any("unclassified production package ru.creditcabinet.cataloggrpc" in error for error in errors), errors)
        self.assertIn("services/new-service: production service has no configured layers", errors)

    def test_nested_kotlin_and_java_http_subtypes_cannot_cross_into_use_cases(self):
        platform = self.root / "libs/platform-common/src/main"
        kotlin = platform / "kotlin/ru/creditcabinet/common/limits/SharedFailure.kt"
        kotlin.write_text("""package ru.creditcabinet.common.limits
import ru.creditcabinet.common.exception.ApiException
class SharedFailure {
    inner class Throttled(message: String) : ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, message)
}
""")
        java = platform / "java/ru/creditcabinet/common/limits/RetryLater.java"
        java.parent.mkdir(parents=True)
        java.write_text("""package ru.creditcabinet.common.limits;
import ru.creditcabinet.common.exception.ApiException;
public class RetryLater extends ApiException {
    public RetryLater() {
        super(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "try later");
    }
}
""")
        self.write_legacy("credit", "ru.creditcabinet.fixture.application",
                          "import ru.creditcabinet.common.limits.SharedFailure.Throttled\n"
                          "import ru.creditcabinet.common.limits.RetryLater\n"
                          "class Process(val limits: Throttled, val retry: RetryLater)")
        errors = boundaries.check(self.root)["errors"]
        self.assertEqual(2, len(errors), errors)
        self.assertTrue(any("application depends on ru.creditcabinet.common.limits.SharedFailure.Throttled" in error for error in errors))
        self.assertTrue(any("application depends on ru.creditcabinet.common.limits.RetryLater" in error for error in errors))

    def test_compiled_superclass_graph_finds_subtypes_without_kotlin_source(self):
        platform = self.root / "libs/platform-common/build/classes"
        parent = "ru/creditcabinet/common/exception/ApiException"
        java = "ru/creditcabinet/common/limits/RetryLater"
        nested = "ru/creditcabinet/common/limits/SharedFailure$Throttled"

        def write_class(language, name, superclass):
            def utf8(value):
                encoded = value.encode("ascii")
                return struct.pack(">BH", 1, len(encoded)) + encoded
            data = (b"\xca\xfe\xba\xbe" + struct.pack(">HHH", 0, 65, 5)
                    + utf8(name) + struct.pack(">BH", 7, 1)
                    + utf8(superclass) + struct.pack(">BH", 7, 3)
                    + struct.pack(">HHHHHHH", 0x21, 2, 4, 0, 0, 0, 0))
            entry = platform / language / "main" / (name + ".class")
            entry.parent.mkdir(parents=True, exist_ok=True)
            entry.write_bytes(data)

        write_class("java", java, parent)
        write_class("kotlin", nested, java)
        catalog = boundaries.BoundaryCatalog(self.root)
        ancestry, errors = boundaries.compiled_ancestry(self.root)
        self.assertEqual([], errors)
        output = f"""
   ru.creditcabinet.fixture.application.Process -> {java.replace('/', '.')} not found
   ru.creditcabinet.fixture.application.Process -> {nested.replace('/', '.')} not found
"""
        self.assertEqual([], boundaries.bytecode_errors(output, catalog))
        catalog.add_http_descendants(ancestry)
        self.assertEqual(2, len(boundaries.bytecode_errors(output, catalog)))

    def test_shared_adapter_inference_honors_framework_free_types_in_mixed_files(self):
        platform = self.root / "libs/platform-common"
        domain = platform / boundaries.COMMON_DOMAIN_PATH
        domain.mkdir(parents=True)
        consumer = domain / "Delivery.kt"
        neutral = """package ru.creditcabinet.common.domain
import ru.creditcabinet.common.limits.LimitWindow
import ru.creditcabinet.common.mail.OutgoingMail
import ru.creditcabinet.common.model.Inn
import ru.creditcabinet.common.notifications.PlatformNotification
import ru.creditcabinet.common.notifications.PlatformNotifications
import ru.creditcabinet.common.outbox.DeliveryHandler
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.common.security.TokenClaims
interface Delivery {
    fun send(window: LimitWindow, mail: OutgoingMail, inn: Inn, note: PlatformNotification, claims: TokenClaims)
    fun dispatcher(): PlatformNotifications
    fun delivery(): DeliveryHandler
    fun roles(): Roles
}
"""
        consumer.write_text(neutral)
        self.assertEqual([], boundaries.check(self.root)["errors"])

        outbox = domain.parent / "outbox"
        (outbox / "WorkerAccess.kt").write_text("""package ru.creditcabinet.common.outbox
import org.springframework.stereotype.Component

interface WorkerAccess {
    fun worker(): OutboxWorker
}
class SchedulingEndpoint(private val access: WorkerAccess)
@Component class WorkerRegistration
""")
        consumer.write_text(neutral + """
class Leak(
    val worker: ru.creditcabinet.common.outbox.OutboxWorker,
    val endpoint: ru.creditcabinet.common.outbox.SchedulingEndpoint,
    val registration: ru.creditcabinet.common.outbox.WorkerRegistration,
)
""")
        report = boundaries.check(self.root)
        self.assertEqual(3, len(report["errors"]), report)
        for symbol in ("OutboxWorker", "SchedulingEndpoint", "WorkerRegistration"):
            self.assertTrue(any(f"domain depends on ru.creditcabinet.common.outbox.{symbol}" in error for error in report["errors"]), report)

    def test_shared_bytecode_dependencies_propagate_from_framework_to_consumers(self):
        catalog = boundaries.BoundaryCatalog(self.root)
        consumer = """
   ru.creditcabinet.common.domain.Core -> ru.creditcabinet.common.outbox.OutboxWorker main
   ru.creditcabinet.common.domain.Core -> ru.creditcabinet.common.schedule.Facade main
   ru.creditcabinet.common.domain.Core -> ru.creditcabinet.common.outbox.DeliveryHandler main
   ru.creditcabinet.common.domain.Core -> ru.creditcabinet.common.security.TokenClaims main
"""
        self.assertEqual(1, len(boundaries.bytecode_errors(consumer, catalog, common_domain_only=True)))
        shared = """
   ru.creditcabinet.common.schedule.Facade -> ru.creditcabinet.common.schedule.Wrapped main
   ru.creditcabinet.common.schedule.Wrapped -> ru.creditcabinet.common.schedule.Access main
   ru.creditcabinet.common.schedule.Access$Initializer -> org.springframework.jdbc.core.JdbcTemplate not found
"""
        catalog.add_shared_adapters(boundaries.shared_bytecode_edges(shared))
        errors = boundaries.bytecode_errors(consumer, catalog, common_domain_only=True)
        self.assertEqual(2, len(errors), errors)
        self.assertTrue(any("common.schedule.Facade" in error for error in errors))
