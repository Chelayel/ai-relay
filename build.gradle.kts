plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    application
}

group = "com.chelayel.airelay"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // The only runtime dependency: Gson, for the Gemini/Vertex REST payloads and
    // for parsing the Claude CLI's stream-json output. Everything else is JDK.
    implementation("com.google.code.gson:gson:2.11.0")

    // Test-only. The Copilot backend parses shell command lines, unknown JSON
    // shapes and a streamed fence protocol; none of that can be exercised
    // without a live browser session, so it is covered here instead.
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.chelayel.airelay.MainKt")
    applicationName = "airelay"
}
