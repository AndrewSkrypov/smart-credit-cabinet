import com.github.jk1.license.render.JsonReportRenderer

buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

plugins {
    `kotlin-dsl`
    alias(libs.plugins.license.report)
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.allopen)
    implementation(libs.kotlin.noarg)
    implementation(libs.spring.boot.gradle.plugin)
    implementation(libs.spring.dependency.management.plugin)
    implementation(libs.ktlint.gradle.plugin)
}

dependencyLocking {
    lockAllConfigurations()
}

tasks.register("resolveAndLockAll") {
    notCompatibleWithConfigurationCache("Resolves the final set of build configurations")
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) { "Используйте --write-locks для обновления lock-файлов" }
    }
    doLast {
        configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
    }
}

licenseReport {
    configurations = emptyArray()
    buildScriptProjects = arrayOf(project)
    renderers = arrayOf(JsonReportRenderer("build-logic-dependencies.json", false))
}
