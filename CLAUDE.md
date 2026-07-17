# AI Relay — project guide

A single CLI that runs **Claude** and **Gemini** as coding agents over the current
repo. It is the CLI descendant of the two JetBrains "Relay" plugins
(`claude-code-gui`/Claude Relay, `gemini-relay`/Gemini Relay): the connection code
is ported from them with all IntelliJ dependencies stripped, and tool access is
widened from a single confined project dir to the whole workspace (repo root +
`--add-dir` folders).

## Build & run

- JDK 21, Gradle 9 (wrapper included). Kotlin/JVM + the `application` plugin.
- `./gradlew compileKotlin` — fast compile check.
- `./gradlew installDist` — launcher at `build/install/airelay/bin/airelay`.
- `./gradlew run --args="claude 'hello'"` — run from Gradle.

## Architecture

- `Main.kt` — arg parsing, backend selection, one-shot vs. REPL.
- `cli/Agent` — the `Agent` interface (both backends) + `PermissionMode`.
- `cli/Console` — the `Sink` event interface + `ConsoleSink` (ANSI) renderer.
- `cli/Workspace` — the allowed directories (repo root + extra dirs); path scoping.
- `config/Config` — env vars overlaid on `~/.airelay/config.properties`.
- `claude/ClaudeAgent` — drives one long-lived `claude` stream-json process across
  turns (prompt-cache warm); `ClaudeCli` locates the executable. **Auto-auth**:
  reuses the `claude` CLI's own login; no keys handled here.
- `gemini/api/GeminiConfig` — typed view over `Config`; the three `ConnectionMode`s.
- `gemini/api/AuthProvider` — credential per mode (API key / gcloud token / Apigee
  OAuth), cached.
- `gemini/api/GeminiClient` — one streaming `streamGenerateContent` call + SSE parse.
- `gemini/agent/Tools` — read/write/list/search/run, scoped to the workspace.
- `gemini/agent/GeminiAgent` — the agentic loop (stream → run tools → repeat).

## Conventions

- Keep the two backends behind the `Agent`/`Sink` interfaces so they look identical
  at the prompt despite different transports. **The connection is the only real
  difference** — don't leak backend specifics into `cli/`.
- Parse REST/SSE/CLI output defensively — never crash on bad input.
- Tool file access is confined to `Workspace.roots`; keep it that way.
- No IntelliJ APIs. Only runtime dependency is Gson.
