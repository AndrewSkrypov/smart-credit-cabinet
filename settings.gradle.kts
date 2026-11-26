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

include(":libs:servicing-model")
include(":libs:servicing-core")
include(":libs:servicing-persistence")
include(":services:servicing")

include(":libs:servicing-contracts")
include(":libs:servicing-grpc")
