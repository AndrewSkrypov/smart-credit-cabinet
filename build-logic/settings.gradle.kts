pluginManagement {
    repositories {
        if (providers.gradleProperty("cabinetKotlinCentralMirror").orNull == "true") {
            exclusiveContent {
                forRepository {
                    maven("https://maven-central.storage-download.googleapis.com/maven2/") {
                        name = "KotlinCentralMirror"
                        mavenContent { releasesOnly() }
                    }
                }
                filter { includeGroup("org.jetbrains.kotlin") }
            }
        }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
