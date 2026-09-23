package com.chelayel.airelay.cli

import java.io.File

/**
 * `--worktree`: run the agent on its own branch, in a git worktree of the
 * repo, so the checkout the IDE shows is untouched until the branch is merged.
 * Worktrees live under `~/.airelay/worktrees/<repo>/<name>` and the branch is
 * named after the worktree.
 */
object Worktree {

    class Created(val dir: File, val branch: String, val repo: File, val existed: Boolean)

    fun create(start: File, requestedName: String?): Result<Created> {
        val top = git(start, "rev-parse", "--show-toplevel").getOrElse {
            return Result.failure(IllegalStateException("--worktree needs a git repository: ${it.message}"))
        }.trim().let(::File)
        val name = requestedName?.takeIf { it.isNotBlank() }
            ?: "airelay-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm").format(java.util.Date())
        val safe = name.replace(Regex("[^A-Za-z0-9._/-]"), "-")
        val dir = File(System.getProperty("user.home") ?: ".", ".airelay/worktrees/${top.name}/${safe.replace('/', '-')}")
        if (dir.isDirectory && File(dir, ".git").exists()) return Result.success(Created(dir, safe, top, existed = true))
        dir.parentFile?.mkdirs()
        val branchExists = git(top, "rev-parse", "--verify", "--quiet", "refs/heads/$safe").isSuccess
        val add = if (branchExists) git(top, "worktree", "add", dir.path, safe)
        else git(top, "worktree", "add", "-b", safe, dir.path)
        return add.map { Created(dir, safe, top, existed = false) }
    }

    fun git(dir: File, vararg args: String): Result<String> = runCatching {
        val p = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor() != 0) throw IllegalStateException(out.trim().ifBlank { "git ${args.first()} failed" })
        out
    }
}
