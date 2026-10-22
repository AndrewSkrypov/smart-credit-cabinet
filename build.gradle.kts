import com.github.jk1.license.render.JsonReportRenderer
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

plugins {
    base
    jacoco
    alias(libs.plugins.license.report)
}

group = "ru.creditcabinet"
version = "0.1.0-SNAPSHOT"

jacoco {
    toolVersion = "0.8.15"
}

val coverageModules = subprojects.filter { it.path.startsWith(":services:") || it.path == ":libs:platform-common" }
val coverageClasses = files(coverageModules.map { it.layout.buildDirectory.dir("classes/kotlin/main") })
val coverageExecutions = files(coverageModules.map { it.layout.buildDirectory.file("jacoco/test.exec") })
val domainMinimum = providers.gradleProperty("domainCoverageMinimum").orElse("0.75").get().toBigDecimal()
require(domainMinimum >= "0.75".toBigDecimal() && domainMinimum <= "1.0".toBigDecimal()) { "Порог domain должен быть от 0.75 до 1.0, получено $domainMinimum" }

tasks.register<JacocoReport>("jacocoAggregateReport") {
    group = "verification"
    description = "Общий отчёт JaCoCo по тестам всех сервисов и общей библиотеки"
    dependsOn(coverageModules.map { "${it.path}:test" })
    executionData.setFrom(coverageExecutions)
    classDirectories.setFrom(coverageClasses)
    sourceDirectories.setFrom(coverageModules.map { it.file("src/main/kotlin") })
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

tasks.register<JacocoCoverageVerification>("domainCoverage") {
    group = "verification"
    description = "Проверить покрытие строк каждого пакета domain"
    dependsOn("jacocoAggregateReport")
    executionData.setFrom(coverageExecutions)
    classDirectories.setFrom(coverageClasses.asFileTree.matching { include("**/domain/**") })
    violationRules {
        rule {
            element = "PACKAGE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = domainMinimum
            }
        }
    }
}

tasks.named("check") {
    dependsOn("domainCoverage")
}

allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
    tasks.register("resolveAndLockAll") {
        notCompatibleWithConfigurationCache("Resolves the final set of project configurations")
        doFirst {
            require(gradle.startParameter.isWriteDependencyLocks) { "Используйте --write-locks для обновления lock-файлов" }
        }
        doLast {
            configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
        }
    }
}

tasks.register("updateDependencyLocks") {
    group = "verification"
    description = "Обновить lock-файлы всех модулей; требуется --write-locks"
    dependsOn(allprojects.map { it.tasks.named("resolveAndLockAll") })
}

licenseReport {
    projects = arrayOf(project) + subprojects.filter { it.path.startsWith(":services:") || it.path == ":libs:platform-common" }.toTypedArray()
    buildScriptProjects = arrayOf(project)
    configurations = emptyArray()
    renderers = arrayOf(JsonReportRenderer("backend-dependencies.json", false))
}
