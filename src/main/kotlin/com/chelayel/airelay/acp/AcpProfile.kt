package com.chelayel.airelay.acp

import java.io.File

/**
 * One ACP agent's CLI: how to start it, what to call it, and what to tell the
 * user when it is missing or signed out. [AcpAgent] is the same for all of them.
 */
data class AcpProfile(
    val id: String,
    val name: String,
    val describe: String,
    /** The full command line, executable first. */
    val command: () -> List<String>,
    val install: String,
    val signIn: String,
    /** The CLI's flag for another workspace root, when it has one. */
    val addDirFlag: String? = null,
    /**
     * The CLI's context-window tier flag, when it has one; a launch flag, so a change restarts
     * the CLI. Its tiers are read from the CLI's own `--help` ([contextTiers]), so a tier a
     * new release adds shows up without an update here.
     */
    val contextFlag: String? = null,
) {
    val contextTiers: List<String> by lazy { contextFlag?.let { flag -> helpChoices(command().first(), flag) }.orEmpty() }

    companion object {
        val GITHUB = AcpProfile(
            id = "github", name = "GitHub Copilot", describe = "GitHub Copilot · CLI (signed in with GitHub)",
            command = { listOf(find("copilot") ?: "copilot", "--acp") },
            install = "Install GitHub's Copilot CLI — `brew install copilot-cli` or `npm install -g @github/copilot` — and sign in with `copilot login`.",
            signIn = "Run `copilot login` in a terminal",
            addDirFlag = "--add-dir",
            // Copilot's /model offers the long window on tiered-pricing models; over ACP it is only this flag.
            contextFlag = "--context",
        )

        /**
         * Codex speaks ACP through Zed's adapter, which drives the Codex CLI and its
         * ChatGPT sign-in. It must be installed, like the other CLIs: nothing is
         * fetched from npm behind the user's back.
         */
        val CODEX = AcpProfile(
            id = "codex", name = "Codex", describe = "Codex · CLI (signed in with ChatGPT)",
            command = { listOf(find("codex-acp") ?: "codex-acp") },
            install = "Install the Codex CLI (`npm install -g @openai/codex` or `brew install codex`) and sign in with `codex login`; then install its ACP adapter with `npm install -g @zed-industries/codex-acp`.",
            signIn = "Run `codex login` in a terminal",
        )

        val OPENCODE = AcpProfile(
            id = "opencode", name = "OpenCode", describe = "OpenCode · CLI (its own providers)",
            command = { listOf(find("opencode") ?: "opencode", "acp") },
            install = "Install OpenCode — `curl -fsSL https://opencode.ai/install | bash` or `npm install -g opencode-ai` — and add a provider with `opencode auth login` (opencode-apigee brings your Gemini gateway).",
            signIn = "Run `opencode auth login` in a terminal",
        )

        /** `--flag <x>  … (choices: "a", "b")` in a CLI's help, possibly wrapped over lines; empty when absent. */
        internal fun parseChoices(help: String, flag: String): List<String> {
            val start = Regex("""(^|\s)${Regex.escape(flag)}[\s,=<]""", RegexOption.MULTILINE).find(help)?.range?.first ?: return emptyList()
            // The flag's own entry: up to the next line that starts another option.
            val rest = help.substring(start)
            val end = Regex("""\n\s{0,4}-""").find(rest, 2)?.range?.first ?: rest.length
            val entry = rest.substring(0, end).replace(Regex("""\s+"""), " ")
            val choices = Regex("""choices:\s*([^)]*)\)""").find(entry)?.groupValues?.get(1) ?: return emptyList()
            return Regex("\"([^\"]+)\"").findAll(choices).map { it.groupValues[1] }.toList()
        }

        private fun helpChoices(executable: String, flag: String): List<String> = runCatching {
            val p = ProcessBuilder(executable, "--help").redirectErrorStream(true).start()
            p.outputStream.close()
            val text = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
            parseChoices(text, flag)
        }.getOrDefault(emptyList())

        fun of(id: String): AcpProfile? = listOf(GITHUB, CODEX, OPENCODE).firstOrNull { it.id == id }

        /** An IDE started from the Dock has a short PATH: the usual install places are tried by name too. */
        fun find(name: String): String? {
            System.getenv("AIRELAY_${name.uppercase().replace('-', '_')}_CLI")?.takeIf { File(it).canExecute() }?.let { return it }
            val home = System.getProperty("user.home")
            val win = System.getProperty("os.name").lowercase().contains("win")
            val names = if (win) listOf("$name.cmd", "$name.exe", name) else listOf(name)
            val dirs = System.getenv("PATH").orEmpty().split(File.pathSeparator) + listOf(
                "/opt/homebrew/bin", "/usr/local/bin", "$home/.local/bin", "$home/.npm-global/bin", "$home/.opencode/bin", "$home/AppData/Roaming/npm",
            )
            return dirs.filter { it.isNotBlank() }.flatMap { d -> names.map { File(d, it) } }.firstOrNull { it.canExecute() }?.path
        }
    }
}
