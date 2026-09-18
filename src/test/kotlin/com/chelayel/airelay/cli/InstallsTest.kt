package com.chelayel.airelay.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Installs] tells apart the install routes by where their launcher lives, and
 * points at the older of two so switching routes leaves one behind on purpose,
 * not by accident.
 */
class InstallsTest {

    private val home = "/Users/someone"

    private fun route(target: String, onPath: String = target, windows: Boolean = false, lad: String? = "C:/Users/someone/AppData/Local") =
        Installs.classify(File(onPath), File(target), if (windows) "C:/Users/someone" else home, lad, windows)

    @Test
    fun `unix routes are told apart by their launcher path`() {
        assertEquals(Installs.Route.SCRIPT, route("$home/.local/share/airelay/airelay.app/Contents/MacOS/airelay", "$home/.local/bin/airelay"))
        assertEquals(Installs.Route.HOMEBREW, route("/opt/homebrew/bin/airelay"))
        assertEquals(Installs.Route.HOMEBREW, route("/usr/local/Cellar/airelay/1.1.0/bin/airelay", "/usr/local/bin/airelay"))
        assertEquals(Installs.Route.PKG, route("/Applications/airelay.app/Contents/MacOS/airelay", "/usr/local/bin/airelay"))
        assertEquals(Installs.Route.DEB, route("/opt/airelay/bin/airelay", "/usr/bin/airelay"))
        assertEquals(Installs.Route.DEV, route("/src/ai-relay/build/install/airelay/bin/airelay", "$home/.local/bin/airelay"))
        assertEquals(Installs.Route.OTHER, route("/srv/tools/airelay"))
    }

    @Test
    fun `windows routes are told apart by their launcher path`() {
        val lad = "C:\\Users\\someone\\AppData\\Local"
        assertEquals(Installs.Route.SCOOP, route("C:\\Users\\someone\\scoop\\shims\\airelay.exe", windows = true, lad = lad))
        assertEquals(Installs.Route.SCRIPT_PS, route("$lad\\Programs\\airelay\\airelay.exe", windows = true, lad = lad))
        assertEquals(Installs.Route.MSI, route("$lad\\airelay\\airelay.exe", windows = true, lad = lad))
        assertEquals(Installs.Route.DEV, route("C:\\src\\ai-relay\\build\\install\\airelay\\bin\\airelay.bat", windows = true, lad = lad))
    }

    @Test
    fun `PATH is scanned in order and the same launcher counts once`() {
        val tmp = Files.createTempDirectory("installs").toFile()
        try {
            val a = File(tmp, "a").apply { mkdirs() }
            val b = File(tmp, "b").apply { mkdirs() }
            val real = File(tmp, "real/airelay").apply { parentFile.mkdirs(); writeText("#!/bin/sh\n"); setExecutable(true) }
            Files.createSymbolicLink(File(a, "airelay").toPath(), real.toPath())
            File(b, "airelay").writeText("#!/bin/sh\nexec other\n")
            val path = listOf(a.path, b.path, a.path).joinToString(File.pathSeparator)

            val found = Installs.onPath(path, home = tmp.path, localAppData = null, windows = false)

            assertEquals(listOf(File(a, "airelay"), File(b, "airelay")), found.map { it.onPath })
            assertEquals(real.canonicalFile, found[0].target)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `the message names the older install and how to remove it`() {
        val tmp = Files.createTempDirectory("installs").toFile()
        try {
            val old = File(tmp, "old/airelay").apply { parentFile.mkdirs(); writeText("") ; setLastModified(1_000_000_000_000) }
            val new = File(tmp, "new/airelay").apply { parentFile.mkdirs(); writeText("") ; setLastModified(2_000_000_000_000) }
            val found = listOf(
                Installs.Found(new, new, Installs.Route.HOMEBREW),
                Installs.Found(old, old, Installs.Route.SCRIPT),
            )
            val text = Installs.message(found)
            assertTrue(text.contains("${new.path}  (Homebrew)  <- runs"), text)
            assertTrue(text.contains("${old.path}  (install.sh)  <- older"), text)
            assertTrue(text.contains("rm ~/.local/bin/airelay && rm -rf ~/.local/share/airelay"), text)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
