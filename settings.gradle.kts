pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "smart-credit-cabinet"

include(":libs:platform-common")

// Deployable services, each built and started on its own (see docs/architecture.md).
include(":services:identity")
include(":services:credit")
include(":services:engagement")
include(":services:external-sim")

// The shadow pilot has its own build and database lifecycle.
if (providers.gradleProperty("alphaAnalytics").map { it.toBooleanStrict() }.getOrElse(false)) {
    include(":services:analytics")
}

// Documents retains local Credit behavior by default; its independent runtime has no shared DB.
include(":libs:loan-math", ":libs:documents-model", ":libs:documents-contracts", ":libs:documents-client", ":libs:documents-storage", ":libs:documents-renderer")
include(":services:documents")
