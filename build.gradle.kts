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

// The launcher picks its own JVM.
//
// The stock start script runs on `$JAVA_HOME`, and this tool is used from
// inside other people's repos — a repo that pins JAVA_HOME to a Java 8
// toolchain (sdkman/direnv/jenv, or just a shell export) started `airelay` on
// Java 8, which died with UnsupportedClassVersionError before main() ever ran.
// So resolve a JDK 21+ here instead, falling back to the JDK it was built with.
//
// JAVA_HOME itself is left exactly as it was found, deliberately: the agent
// shells out to `./gradlew` in that same repo, and that build must keep seeing
// the repo's own JDK. Only this process is repointed.
val buildJavaExecutable = extensions.getByType<JavaToolchainService>()
    .launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    .map { it.executablePath.asFile.absolutePath }

tasks.named<CreateStartScripts>("startScripts") {
    inputs.property("buildJavaExecutable", buildJavaExecutable)
    doLast {
        val startMarker = "# Determine the Java command to use to start the JVM."
        val endMarker = "# Increase the maximum file descriptors if we can."
        val script = unixScript.readText()
        // Fail the build rather than silently shipping the stock resolution if a
        // Gradle upgrade renames these: the symptom is a crash in someone else's
        // repo, days later, that looks nothing like a build problem.
        require(script.contains(startMarker) && script.contains(endMarker)) {
            "Cannot patch the JVM resolution into ${unixScript}: Gradle's start " +
                "script template no longer contains the expected markers."
        }
        val resolver = """
            $startMarker
            #
            # Patched by build.gradle.kts: airelay is compiled for Java 21 and is run
            # from inside repos that may pin JAVA_HOME to an older JDK, so pick a
            # usable JVM here. JAVA_HOME is left untouched for child processes.
            airelay_java_major() {
                [ -n "${'$'}1" ] && [ -x "${'$'}1" ] || return 1
                "${'$'}1" -version 2>&1 | awk -F'"' '
                    /version/ { split(${'$'}2, v, /[._-]/); print (v[1] == 1 ? v[2] : v[1]); exit }
                '
            }

            airelay_usable() {
                airelay_major=${'$'}( airelay_java_major "${'$'}1" ) || return 1
                case "${'$'}airelay_major" in ''|*[!0-9]*) return 1 ;; esac
                [ "${'$'}airelay_major" -ge 21 ]
            }

            if [ -n "${'$'}{AIRELAY_JAVA_HOME:-}" ] && ! airelay_usable "${'$'}AIRELAY_JAVA_HOME/bin/java" ; then
                die "ERROR: AIRELAY_JAVA_HOME is set to ${'$'}AIRELAY_JAVA_HOME, which is not a JDK 21 or newer."
            fi

            JAVACMD=""
            for airelay_candidate in \
                "${'$'}{AIRELAY_JAVA_HOME:+${'$'}AIRELAY_JAVA_HOME/bin/java}" \
                "${'$'}{JAVA_HOME:+${'$'}JAVA_HOME/bin/java}" \
                "${'$'}( command -v java 2>/dev/null )" \
                "${buildJavaExecutable.get()}" \
                "${'$'}( /usr/libexec/java_home -v 21+ 2>/dev/null )/bin/java"
            do
                if airelay_usable "${'$'}airelay_candidate" ; then
                    JAVACMD=${'$'}airelay_candidate
                    break
                fi
            done

            if [ -z "${'$'}JAVACMD" ] ; then
                die "ERROR: airelay needs Java 21 or newer and could not find one.

            Tried AIRELAY_JAVA_HOME, JAVA_HOME (${'$'}{JAVA_HOME:-unset}), java on PATH,
            and the JDK airelay was built with (${buildJavaExecutable.get()}).

            Point AIRELAY_JAVA_HOME at a JDK 21+ to override, for example:
              export AIRELAY_JAVA_HOME=/path/to/jdk-21"
            fi

            $endMarker
        """.trimIndent()
        unixScript.writeText(
            script.substringBefore(startMarker) + resolver + script.substringAfter(endMarker)
        )
    }
}
