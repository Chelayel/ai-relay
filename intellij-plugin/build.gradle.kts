plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.17.0"
}

group = "com.chelayel.airelay"
version = (findProperty("releaseVersion") as String?) ?: "1.4.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val cli: Configuration by configurations.creating

dependencies {
    intellijPlatform {
        // IntelliJ IDEA Community; works in every JetBrains IDE built on the platform.
        create("IC", "2024.2.5")
    }
    // The CLI itself, substituted from the repo root by the composite build. Not
    // `implementation`: its jars must not be on the plugin's classloader (they
    // carry a Kotlin stdlib the IDE already has). They go to lib/airelay/ and
    // are only ever the classpath of the subprocess.
    cli("com.chelayel.airelay:ai-relay:1.4.0")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation(kotlin("test"))
}

intellijPlatform {
    instrumentCode = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }
    // `./gradlew publishPlugin` with JETBRAINS_MARKETPLACE_TOKEN set (a token
    // from https://plugins.jetbrains.com/author/me/tokens). The first upload of a
    // new plugin is done by hand on the Marketplace site; this handles updates.
    publishing {
        token = providers.environmentVariable("JETBRAINS_MARKETPLACE_TOKEN")
    }
    pluginVerification {
        ides {
            // JetBrains' pick of IDE builds for the declared since-build range.
            recommended()
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// One chat page for both IDEs: it lives in ide/chat and is copied in at build.
tasks.processResources {
    from(rootDir.resolve("../ide/chat")) { into("chat") }
}

tasks.prepareSandbox {
    from(cli) { into("${intellijPlatform.projectName.get()}/lib/airelay") }
}

tasks.test {
    useJUnitPlatform()
}
