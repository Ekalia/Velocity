@file:Suppress("UnstableApiUsage")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://nexus.ekalia.fr/repository/ekalia/") {
            name = "ekalia"
            credentials {
                username = System.getenv("NEXUS_USERNAME") ?: providers.gradleProperty("ekaliaNexusUser").orNull
                password = System.getenv("NEXUS_PASSWORD") ?: providers.gradleProperty("ekaliaNexusPassword").orNull
            }
            content {
                includeGroup("fr.ekalia")
            }
        }
    }
}

pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "velocity"

sequenceOf(
    "api",
    "native",
    "proxy",
).forEach {
    val project = ":velocity-$it"
    include(project)
    project(project).projectDir = file(it)
}

// Include Configurate 3
val deprecatedConfigurateModule = ":deprecated-configurate3"
include(deprecatedConfigurateModule)
project(deprecatedConfigurateModule).projectDir = file("proxy/deprecated/configurate3")
