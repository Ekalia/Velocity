plugins {
    `java-library`
    `maven-publish`
    id("velocity-spotless") apply false
}

subprojects {
    apply<JavaLibraryPlugin>()

    apply(plugin = "velocity-spotless")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    dependencies {
        testImplementation(rootProject.libs.junit)
    }

    testing.suites.named<JvmTestSuite>("test") {
        useJUnitJupiter()
        targets.all {
            testTask.configure {
                reports.junitXml.required = true
            }
        }
    }
}

allprojects {
    apply(plugin = "maven-publish")

    publishing {
        repositories {
            maven("https://nexus.ekalia.fr/repository/ekalia/") {
                credentials {
                    username = System.getenv("NEXUS_USERNAME")
                    password = System.getenv("NEXUS_PASSWORD")
                }
            }
        }
    }
}
