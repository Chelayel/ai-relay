package com.chelayel.airelay.cli

import java.io.File

/**
 * The set of directories an agent is allowed to see. Unlike the IntelliJ plugins
 * — which confined every tool to a single project root — a CLI agent works over
 * the whole repo it is launched in, plus any extra folders passed with `--add-dir`.
 *
 * [primary] is the process working directory (and the CWD for shell commands and
 * for the `claude` subprocess). [roots] is [primary] followed by the extra dirs;
 * a path is in-scope if it lives under any root.
 */
class Workspace(primary: File, additional: List<File>) {
    val primary: File = primary.absoluteFile.canonicalFile
    val roots: List<File> = (listOf(primary) + additional)
        .map { it.absoluteFile.canonicalFile }
        .distinctBy { it.path }

    /** True when [file] is the same as, or nested under, one of the roots. */
    fun contains(file: File): Boolean {
        val target = file.absoluteFile.canonicalFile.path
        return roots.any { root -> target == root.path || target.startsWith(root.path + File.separator) }
    }

    companion object {
        fun of(dir: String?, addDirs: List<String>): Workspace {
            val primary = dir?.let { File(it) } ?: File(System.getProperty("user.dir"))
            return Workspace(primary, addDirs.map { File(it) })
        }
    }
}
