package ru.creditcabinet.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import kotlin.test.Test

class HexagonalArchitectureTest {
    private val classes = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("ru.creditcabinet")

    @Test
    fun `domain layer does not depend on web, persistence, controllers or infrastructure`() {
        noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "..controller..",
                "..infrastructure..",
                "org.springframework.web..",
                "jakarta.persistence..",
                "org.springframework.jdbc..",
            )
            .check(classes)
    }

    @Test
    fun `controllers do not depend directly on infrastructure or persistence`() {
        noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "..infrastructure..",
                "jakarta.persistence..",
                "org.springframework.jdbc..",
            )
            .check(classes)
    }

    @Test
    fun `application layer does not depend on controllers or web annotations`() {
        noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "..controller..",
                "org.springframework.web.bind.annotation..",
            )
            .check(classes)
    }
}
