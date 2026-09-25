plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    application
}

group = "com.chelayel.airelay"
// A release build passes the tag: -PreleaseVersion=1.2.3 (see release.yml).
version = (findProperty("releaseVersion") as String?) ?: "2.1.1"

repositories {
    mavenCentral()
}

dependencies {
    // Gson, for the Gemini/Vertex REST payloads and for parsing the Claude CLI's
    // stream-json output.
    implementation("com.google.code.gson:gson:2.11.0")

    // JLine, for the prompt. Telling Enter from Ctrl+J from Alt+Enter, bracketed
    // paste and history all need the terminal in raw mode, and the JDK cannot
    // put it there: `stty` would cover macOS and Linux, but there is no such
    // thing on Windows, where the console mode is a Win32 call. The jni provider
    // carries that native code for every platform in its jar. JLine 3, not 4:
    // 3.x still runs on the Java 21 this is compiled for without the FFM API.
    implementation("org.jline:jline-reader:3.30.17")
    implementation("org.jline:jline-terminal:3.30.17")
    implementation("org.jline:jline-terminal-jni:3.30.17")

    // Test-only. The Copilot backend parses shell command lines, unknown JSON
    // shapes and a streamed fence protocol; none of that can be exercised
    // without a live browser session, so it is covered here instead.
    testImplementation(kotlin("test"))
}

tasks.jar {
    manifest { attributes("Implementation-Title" to "airelay", "Implementation-Version" to project.version) }
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
    // The Windows console defaults to a legacy code page, on which every ✓, ⏺
    // and › prints as `?`. Java 19+ honours these for System.out/err.
    // Serial GC and the C1 compiler only: this is a small, mostly-idle process, and
    // the defaults size threads and heap for a server. Measured: ~10 MB less at
    // start, no cost in startup time.
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xshare:auto")
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
            and /usr/libexec/java_home.

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

// ---- native packages ---------------------------------------------------------
//
// `installDist` needs a JDK 21 on the machine, which is the one thing someone who
// is not a developer does not have and cannot be asked to get. jpackage bundles a
// trimmed runtime with the app, so what ships is self-contained: an .msi on
// Windows, a .pkg on macOS, a .deb on Linux, and on all three a plain app image
// (a folder with a native launcher in it) for Homebrew, Scoop and install.sh.
//
// jpackage cannot cross-build — each package is made on its own OS — so these
// run on a CI matrix (.github/workflows/release.yml), not on one laptop.

val os = org.gradle.internal.os.OperatingSystem.current()

// Listed by hand rather than left to jlink's guess. jdeps sees no reference to
// jdk.crypto.ec, because TLS loads it by name — and without it on Java 21 every
// HTTPS call to a host with an EC certificate fails its handshake.
// jdk.crypto.mscapi is the Windows-ROOT trust store (Trust.kt reads the OS's certificates
// through it); it exists only in Windows JDKs, so it is added only there.
val runtimeModules = (listOf(
    "java.base", "java.net.http", "java.logging", "java.sql", "java.naming",
    "java.security.jgss", "java.management", "jdk.unsupported", "jdk.crypto.ec", "jdk.charsets",
) + (if (os.isWindows) listOf("jdk.crypto.mscapi") else emptyList())).joinToString(",")

// macOS refuses a bundle version starting with 0 (CFBundleShortVersionString).
val packageVersion = version.toString().let { if (os.isMacOsX && it.startsWith("0.")) "1.${it.substring(2)}" else it }

val jpackageExecutable = extensions.getByType<JavaToolchainService>()
    .launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    .map { it.metadata.installationPath.file(if (os.isWindows) "bin/jpackage.exe" else "bin/jpackage").asFile.absolutePath }

fun Exec.jpackage(type: String, destination: Provider<Directory>, extra: List<String> = emptyList()) {
    group = "distribution"
    dependsOn("installDist")
    val lib = layout.buildDirectory.dir("install/airelay/lib")
    inputs.dir(lib)
    inputs.dir("packaging")
    outputs.dir(destination)
    doFirst {
        // jpackage will not overwrite an app image it made earlier.
        delete(destination)
        commandLine(
            listOf(
                jpackageExecutable.get(),
                "--type", type,
                "--name", "airelay",
                "--app-version", packageVersion,
                "--vendor", "Chelayel",
                "--description", "Claude, Gemini and Copilot as CLI coding agents",
                "--input", lib.get().asFile.path,
                "--main-jar", tasks.named<Jar>("jar").get().archiveFileName.get(),
                "--main-class", application.mainClass.get(),
                "--add-modules", runtimeModules,
                "--java-options", "-Dstdout.encoding=UTF-8",
                "--java-options", "-Dstderr.encoding=UTF-8",
                "--java-options", "-XX:+UseSerialGC",
                "--java-options", "-XX:TieredStopAtLevel=1",
                "--resource-dir", file("packaging/${if (os.isMacOsX) "macos" else if (os.isWindows) "windows" else "linux"}").path,
                "--dest", destination.get().asFile.path,
            ) + extra,
        )
    }
}

/** The app image: `build/jpackage/image/airelay[.app]`. */
tasks.register<Exec>("jpackageImage") {
    description = "Self-contained app image with a bundled Java runtime."
    jpackage("app-image", layout.buildDirectory.dir("jpackage/image"), if (os.isWindows) listOf("--win-console") else emptyList())
}

/** The installer for the OS this runs on. */
tasks.register<Exec>("jpackageInstaller") {
    description = "Native installer for this OS: .msi, .pkg or .deb."
    val (type, extra) = when {
        os.isWindows -> "msi" to listOf(
            "--win-console",
            // Per-user: no administrator prompt, which a locked-down work
            // laptop would refuse.
            "--win-per-user-install", "--win-menu", "--win-dir-chooser",
            // Fixed, so a newer .msi upgrades in place instead of installing beside.
            "--win-upgrade-uuid", "6f0d3c1e-5a0b-4a57-9d0e-8a6c1f1b7e21",
        )
        os.isMacOsX -> "pkg" to listOf("--mac-package-identifier", "com.chelayel.airelay")
        else -> "deb" to listOf("--linux-shortcut")
    }
    jpackage(type, layout.buildDirectory.dir("jpackage/installer"), extra)
}
