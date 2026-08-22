# AI Relay — project guide

A single CLI that runs **Claude**, **Gemini** and **Copilot** as coding agents over
the current repo. It is the CLI descendant of the two JetBrains "Relay" plugins
(`claude-code-gui`/Claude Relay, `gemini-relay`/Gemini Relay): the connection code
is ported from them with all IntelliJ dependencies stripped, and tool access is
widened from a single confined project dir to the whole workspace (repo root +
`--add-dir` folders). The Copilot backend is new to the CLI — it reuses the user's
signed-in Copilot web session rather than any API.

## Build & run

- JDK 21, Gradle 9 (wrapper included). Kotlin/JVM + the `application` plugin.
- `./gradlew compileKotlin` — fast compile check.
- `./gradlew installDist` — launcher at `build/install/airelay/bin/airelay`.
- `./gradlew run --args="claude 'hello'"` — run from Gradle.
- `./gradlew test` — unit tests (the Copilot parsers; nothing else is covered).

## Architecture

- `Main.kt` — arg parsing, backend selection, one-shot vs. REPL.
- `cli/Agent` — the `Agent` interface (all backends) + `PermissionMode`.
- `cli/Console` — the `Sink` event interface + `ConsoleSink` (ANSI) renderer.
- `cli/Workspace` — the allowed directories (repo root + extra dirs); path scoping.
- `config/Config` — env vars overlaid on `~/.airelay/config.properties`.
- `agent/Tools` — read/write/list/search/run, scoped to the workspace; shared by
  the Gemini and Copilot agents. `agent/ToolSpec` is the backend-neutral
  declaration each one renders into its own transport.
- `claude/ClaudeAgent` — drives one long-lived `claude` stream-json process across
  turns (prompt-cache warm); `ClaudeCli` locates the executable. **Auto-auth**:
  reuses the `claude` CLI's own login; no keys handled here.
- `gemini/api/GeminiConfig` — typed view over `Config`; the three `ConnectionMode`s.
- `gemini/api/AuthProvider` — credential per mode (API key / gcloud token / Apigee
  OAuth), cached.
- `gemini/api/GeminiClient` — one streaming `streamGenerateContent` call + SSE parse.
- `gemini/agent/GeminiAgent` — the agentic loop (stream → run tools → repeat).
- `copilot/CopilotSetup` — the capture wizard. Default route drives a browser;
  `--file` reads a saved "Copy as cURL". Either way it derives the endpoint,
  session headers and field paths.
- `copilot/api/DevTools` — a minimal Chrome DevTools Protocol client over the
  JDK's own `java.net.http.WebSocket`. No new dependency.
- `copilot/api/BrowserCapture` — launches (or attaches to) Chrome/Edge, waits for
  the user's SSO login and a nonce message, and reads that request off the wire.
  Merges `requestWillBeSent` with `requestWillBeSentExtraInfo`, which is the only
  event carrying cookies.
- `copilot/api/CurlImport` — parses that cURL (bash / cmd / PowerShell flavours),
  and flags a capture cut off mid-quote.
- `copilot/api/BodyTemplate` — finds the prompt and model fields in the captured
  body by path, and re-renders it per turn. `Json` is the path helper.
- `copilot/api/CopilotConfig` — typed view over `Config` for the saved capture.
- `copilot/api/CopilotClient` — replays the request (transport only).
- `copilot/api/ResponseReader` — sniffs SSE / NDJSON / JSON / plain text and
  pulls assistant text out of an unknown shape (`TextExtractor`, `TextAssembler`).
- `copilot/agent/CopilotProtocol` — the prompt-taught tool protocol and the
  `ToolBlockFilter` that hides tool fences from the live transcript.
- `copilot/agent/CopilotAgent` — the same agentic loop over that protocol.

## Conventions

- Keep the backends behind the `Agent`/`Sink` interfaces so they look identical
  at the prompt despite different transports. **The connection is the only real
  difference** — don't leak backend specifics into `cli/` or `agent/`.
- Parse REST/SSE/CLI output defensively — never crash on bad input. This matters
  most for Copilot, whose endpoint is undocumented and changes without notice:
  **never hard-code its URL, body shape or response shape.** Everything comes
  from the user's capture, and an unrecognised response must degrade to a
  diagnostic (raw sample + which key to configure), never to a crash.
- The saved Copilot capture contains a live session token. It belongs only in
  the owner-only config file — never log it, never echo it back at the terminal.
- **Never read a capture through the terminal.** A tty in canonical mode
  truncates a single line at ~4 KB; a Copilot request is far larger, and the
  fragment still parses, so it fails later looking like a server fault. Captures
  come from the browser or from a file, and `CurlImport.Captured.truncated`
  rejects a short one.
- Tool file access is confined to `Workspace.roots`; keep it that way.
- No IntelliJ APIs. Only runtime dependency is Gson (kotlin-test for tests).
