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
- `./gradlew test` — unit tests (the Copilot parsers, `agent/Web` against a
  throwaway local HTTP server, and `mcp/McpConfig`).
- **`build.gradle.kts` patches the JVM resolution into the start script.** The
  stock one runs on `$JAVA_HOME`, and this tool is launched from inside other
  people's repos: one that pins `JAVA_HOME` to a Java 8 toolchain started
  `airelay` on Java 8, which died with `UnsupportedClassVersionError` before
  `main()` ran. The patched script picks the first usable JDK 21+ from
  `AIRELAY_JAVA_HOME`, `JAVA_HOME`, `java` on PATH, the JDK it was built with,
  then `/usr/libexec/java_home`. It never assigns `JAVA_HOME` — that variable is
  exported, and the agent shells out to `./gradlew` in that same repo, which
  must keep seeing the repo's own JDK. If Gradle renames the markers it splices
  between, the build fails rather than silently shipping the stock resolution.

Three subcommands exist to prove the plumbing without spending a model turn:
`airelay gemini models`, `airelay web` (fetches, searches and queries Maven
Central live), `airelay mcp` (starts every configured server and lists its tools).

## Architecture

- `Main.kt` — arg parsing, backend selection, one-shot vs. REPL.
- `cli/Agent` — the `Agent` interface (all backends) + `PermissionMode`.
- `cli/Console` — the `Sink` event interface + `ConsoleSink` (ANSI) renderer.
- `cli/Workspace` — the allowed directories (repo root + extra dirs); path scoping.
- `config/Config` — env vars overlaid on `~/.airelay/config.properties`.
- `agent/Tools` — the tool set, shared by the Gemini and Copilot agents. Its own
  workspace tools are `readFile` (whole or a line range), `editFile`
  (exact-snippet replace), `writeFile` (create/replace/append), `listFiles`,
  `searchFiles`, `runCommand`; it then **composes `agent/Web` and `mcp/McpManager`**
  so both backends get web access and MCP servers from one place rather than each
  wiring them up. `isExternal` marks the MCP ones, which the permission rules
  treat as edits rather than as read-only built-ins: an MCP tool is somebody
  else's code and is not confined to the workspace.
  **`editFile` is the one that matters for Copilot**: a chat composer caps a
  message at a few KB, so rewriting a whole file is out of reach for anything
  real. An ambiguous or absent snippet is an error, never a guess — editing the
  wrong occurrence silently is worse than being asked for more context. `agent/ToolSpec` is the backend-neutral
  declaration each one renders into its own transport.
- `agent/Web` — `fetchUrl`, `webSearch` and `mavenSearch`. This exists because an
  agent that can only read the repo answers every question about the world from
  training memory, which is how a Spring Boot 2→4 / Java 8→21 upgrade produced
  confident, invented artifact coordinates and property names. **There is no
  keyless search fallback on purpose**: DuckDuckGo's html and lite endpoints and
  Mojeek all answer an automated client with a challenge page, and a scraper that
  silently returns nothing is worse than no search — the model reads "no results"
  as "nothing exists" and goes back to guessing. So `webSearch` needs a provider
  (brave / tavily / google CSE) and is **not advertised at all until one is
  configured**, while `fetchUrl` and `mavenSearch` need no key and always work.
  `mavenSearch` is there for the specific fact models get wrong most often on an
  upgrade — which group an artifact ships under, and which versions exist.
  Deliberately not Gemini's native `googleSearch` grounding: that is Gemini-only,
  can't always be combined with function declarations, and an Apigee gateway is
  free to strip it. `airelay web` probes all three live.
- `mcp/McpClient` — JSON-RPC-over-stdio MCP client (handshake, `tools/list`,
  `tools/call`), ported from Gemini Relay with `GeneralCommandLine` replaced by
  `ProcessBuilder`. **Its stderr is drained on its own thread**: MCP servers log
  chattily, an undrained pipe fills and the server blocks writing to it, which
  looks from here like a server that handshook and then stopped answering. The
  tail is kept, because a server that dies on startup says why only on stderr.
- `mcp/McpConfig` — servers read from the **same `mcpServers` file shape Claude
  Desktop and Claude Code use** (`$AIRELAY_MCP_CONFIG` / `mcp.config`, then
  `~/.airelay/mcp.json`, then `.mcp.json` in the repo). Sharing the format is the
  point: a server configured once should not have to be declared again here.
- `mcp/McpManager` — owns the servers, merges their tools as backend-neutral
  `ToolSpec`s (not Gemini `FunctionDecl`s, so Copilot gets them too), routes
  calls back. A server that fails to start contributes no tools and one line of
  explanation. `airelay mcp` lists them and proves they start.
- `claude/ClaudeAgent` — drives one long-lived `claude` stream-json process across
  turns (prompt-cache warm); `ClaudeCli` locates the executable. **Auto-auth**:
  reuses the `claude` CLI's own login; no keys handled here.
- `gemini/api/GeminiConfig` — typed view over `Config`; the three `ConnectionMode`s.
  Also the model catalogue: the default (`gemini-3.7-flash`), the per-mode
  shortlist, and `canonicalModel` — Vertex and the Gemini API name the same model
  differently (`gemini-3.1-pro` / `gemini-3.1-pro-preview`), and Google retires
  ids outright, so a saved model is translated or replaced on read rather than
  left to 404 on every turn. `airelay gemini models` prints the live list
  (`GeminiClient.listModels`, Gemini API mode) or the shortlist.
- `gemini/api/AuthProvider` — credential per mode (API key / gcloud token / Apigee
  OAuth), cached.
- `gemini/api/GeminiClient` — one streaming `streamGenerateContent` call + SSE parse.
- `gemini/agent/GeminiAgent` — the agentic loop (stream → run tools → repeat).
  Two limits here were sized for a question, not for a migration, and are now
  config-driven (`gemini.max.tool.rounds`, default 300; `gemini.history.window`,
  default 240). **`trimmed()` is not a `takeLast`**: that could start the window
  on `functionResponse` parts whose `functionCall` had just been cut away, which
  Gemini rejects outright, and it dropped the very first message — the task
  itself — so a job that ran long enough to trim forgot what it was doing. The
  window is advanced past orphaned results, and the opening request is always
  kept with a note that the middle is gone.
- `copilot/CopilotSetup` — the capture wizard. Default route drives a browser;
  `--file` reads a saved "Copy as cURL". Either way it derives the endpoint,
  session headers and field paths.
- `copilot/api/DevTools` — a minimal Chrome DevTools Protocol client over the
  JDK's own `java.net.http.WebSocket`. No new dependency. Events dispatch on
  their own thread, not the socket reader's: handlers make CDP calls of their
  own (`getRequestPostData`, `getResponseBody`) and a blocking call issued from
  the reader thread can never receive its reply.
- `copilot/api/BrowserCapture` — launches (or attaches to) Chrome/Edge, waits for
  the user's SSO login and a nonce message, and reads that request off the wire.
  Merges `requestWillBeSent` with `requestWillBeSentExtraInfo`, which is the only
  event carrying cookies. **Never take the first request carrying the message**:
  a Copilot page fans it out to several endpoints, and the page-state one (which
  replies with the app's store and the message as a chat title) usually fires
  first. Collect them all, read each reply, and rank. Autocomplete
  (`/search/api/v1/suggestions`) also sees every keystroke and replies fast, so
  bookkeeping paths are penalised beyond anything a non-streaming reply can
  earn. When a WebSocket carried the message and every HTTP candidate looks
  inert, chat runs on the socket — say so and offer browser mode, rather than
  saving a capture that connects and answers nothing. **M365 Copilot is this
  case**: its chat is a WebSocket, so browser mode is the only thing that works.
- `copilot/api/CurlImport` — parses that cURL (bash / cmd / PowerShell flavours),
  and flags a capture cut off mid-quote.
- `copilot/api/BodyTemplate` — finds the prompt and model fields in the captured
  body by path, and re-renders it per turn. `Json` is the path helper.
- `copilot/api/CopilotConfig` — typed view over `Config` for the saved capture.
- `copilot/api/CopilotClient` — replays the request (transport only). Retries
  once over HTTP/1.1 when a host answers HTTP/2 with `RST_STREAM: Use HTTP/1.1`,
  as Substrate does.
- `copilot/api/ResponseReader` — sniffs SSE / NDJSON / JSON / plain text and
  pulls assistant text out of an unknown shape (`TextExtractor`, `TextAssembler`).
  `survey()` does the opposite for `airelay copilot diagnose`: record everything
  and let `ResponseSurvey` rank which field is the answer, rather than guessing.
- `copilot/agent/CopilotProtocol` — the prompt-taught tool protocol and the
  `ToolBlockFilter` that hides tool fences from the live transcript. The
  contract goes out **last** in the preamble and is restated briefly on every
  later turn: this is a chat surface, and its model drifts back to chatting —
  asked to write a test it writes one out in prose, and nothing is saved. A
  reply with a code fence but no tool call earns one nudge before being taken
  as the final answer. `offersToContinue` catches the other way a chat model
  stops short — "would you like me to…" — which is an assistant checking in and
  an agent leaving the job half done.
- `copilot/agent/CopilotProtocol` (dictation) — M365 Copilot often refuses to
  emit a write call at all, on the ground that doing so would be pretending to
  execute something: *"the text would only be plain text here, not an actual
  tool invocation."* The objection is sound from where it sits, and no rewording
  of the contract moves it. What it will do, readily, is write the file out in a
  fence — which was this backend's original failure, code produced and nothing
  saved. So `dictatedFiles` saves it: the harness does the executing, which was
  always true. Guarded hard, because a fence is not always a file. An output
  language (`text`, `sh`, `console`, `diff`) is never one; a body that reads as a
  terminal is rejected by ratio, so a real file *mentioning* `BUILD SUCCESSFUL`
  still saves; and a path found only in prose is attributed only when there is
  exactly one block to attribute it to. Without that last rule a turn that
  reported `./gradlew test` had its build log written into `SmokeTest.kt`.
- `copilot/agent/CopilotAgent` — a turn ends only when the work is done, not when
  the model stops talking: describing instead of applying, changing files without
  running anything, and offering to continue are each pushed back on once (three
  pushes a turn, so a model that is genuinely finished can finish). The same call
  repeated with the same arguments is refused after twice, so a turn cannot spin.
- `copilot/api/CopilotTransport` — how a turn reaches Copilot: `ReplayTransport`
  re-sends a captured HTTP request, `BrowserTransport` drives the page. The
  agentic loop is written once against this.
- `copilot/api/CopilotBrowser` — browser mode: type into the composer, press
  Enter, and read the answer off the frames the page's own WebSocket receives
  (reusing `TextExtractor`). When the socket yields nothing readable, read the
  **newest message block**, not a diff of the whole body: a chat page echoes the
  message it was just given and often renders the reply twice, so a body diff
  comes back as our own prompt plus the answer twice — and our prompt contains
  an example tool call, which would then be run as if Copilot had asked for it.
  `cleanPageText` subtracts our own lines as a second line of defence, and
  identical calls within one reply collapse to one. It normalises `<br>` to a
  newline first: a page renders a multi-line message with those tags, reading it
  back yields them as literal text, and every line-wise comparison then misses —
  which let the entire echoed prompt through as the answer. What survives that
  also has our message peeled off its front, because the page puts the message
  it was just given immediately before the reply and often with nothing between
  them.
  **Attach and `Network.enable` before navigating**: CDP reports frames only for
  sockets created while the domain is enabled, so a page loaded first streams
  its whole conversation past unseen. When a socket exists, only it votes on
  whether a turn is progressing — a chat page echoes the message it was just
  given, and counting that as the reply ends the turn seconds after Enter and
  returns our own prompt as the answer.
  **A send is retried on a reloaded page.** Roughly half of turns died with the
  message still in the composer: the box accepts text and swallows Enter, no
  conversation is created, and the turn waits out its patience for an answer to
  a question never asked. Pressing harder does not move a wedged editor —
  reloading builds a new one, and the conversation lives on Copilot's side so a
  reload costs nothing. `awaitSubmitted` proves the box emptied before any of
  this; a turn that cannot send says so instead of scraping the page, which is
  how one came back holding the conversation sidebar dressed up as a reply.
  Finding the composer is the only DOM dependency, and it's overridable with
  `copilot.selector.input`. A composer we can name outright is tried first
  (`KNOWN_COMPOSERS_LIST`) — matching by shape picks the last of several boxes,
  which is a guess. Re-find it **before every message**, not once per
  session: a chat page rebuilds its composer after each turn, so the element
  from turn one is detached by turn two. Focus via a real click as well as
  `focus()`, verify the text landed by reading it back with whitespace
  collapsed (a contenteditable reflows newlines, so an exact compare never
  matches a multi-line message), and on failure report the boxes seen and what
  the box held — never just "could not find it".
- `copilot/api/Browsers` — finding, launching and attaching to Chrome/Edge, with
  or without a window. `copilot.headless=auto` shows one until a profile exists
  and hides it after: signing in needs a window, using it does not. A hidden
  browser that finds no composer is treated as a lapsed sign-in and reopened
  visibly, because from headless that is indistinguishable from a page that
  never loads.
- `copilot/agent/CopilotAgent` — the same agentic loop over that protocol,
  against whichever transport is configured. The first message carries a sketch
  of the workspace (path + a breadth-first file listing): with only a tool
  catalogue and no idea what it would find, a chat-tuned model does not go
  exploring — it answers "I don't have access to the project's code, please
  paste it", which is true from where it is sitting.

## Conventions

- Keep the backends behind the `Agent`/`Sink` interfaces so they look identical
  at the prompt despite different transports. **The connection is the only real
  difference** — don't leak backend specifics into `cli/` or `agent/`.
- Parse REST/SSE/CLI output defensively — never crash on bad input. This matters
  most for Copilot, whose endpoint is undocumented and changes without notice:
  **never hard-code its URL, body shape or response shape.** Everything comes
  from the user's capture, and an unrecognised response must degrade to a
  diagnostic (raw sample + which key to configure), never to a crash. When the
  key list is the thing that's wrong, `copilot diagnose` finds the field from
  the response itself — prefer that over adding more guesses to `DEFAULT_KEYS`.
- The saved Copilot capture contains a live session token. It belongs only in
  the owner-only config file — never log it, never echo it back at the terminal.
- **Never read a capture through the terminal.** A tty in canonical mode
  truncates a single line at ~4 KB; a Copilot request is far larger, and the
  fragment still parses, so it fails later looking like a server fault. Captures
  come from the browser or from a file, and `CurlImport.Captured.truncated`
  rejects a short one.
- Tool file access is confined to `Workspace.roots`; keep it that way.
- No IntelliJ APIs. Only runtime dependency is Gson (kotlin-test for tests).
- A new capability is off unless it can work. `webSearch` is not declared
  without a provider, and MCP contributes nothing when no config file exists —
  neither is an error, and neither costs a round to discover.

## Config keys

Beyond the per-backend connection keys, in `~/.airelay/config.properties` or as
`AIRELAY_`-prefixed env vars:

| Key | Default | What it does |
| --- | --- | --- |
| `web.enabled` | `true` | Master switch; `false` takes the agent offline. |
| `search.provider` | — | `brave`, `tavily` or `google`. Unset = no `webSearch`. |
| `search.api.key` | — | That provider's key. Set alone, it implies `brave`. |
| `search.cx` | — | Google Programmable Search engine id (`google` only). |
| `mcp.config` | — | Path to an `mcpServers` JSON file, overriding the search order. |
| `gemini.max.tool.rounds` | `300` | Tool rounds before the loop gives up. |
| `gemini.history.window` | `240` | Turns kept in the prompt before trimming. |
| `gemini.thinking.level` | — | `low`/`medium`/`high` on 3.x. Nothing on the wire when unset. |
| `gemini.thinking.budget` | — | Token budget on 2.5. Set one or the other, not both. |

The thinking keys are opt-in rather than defaulted because an Apigee gateway
publishes its own model ids: there is no way to tell from here whether the
model behind one takes `thinkingLevel`, `thinkingBudget` or neither, and sending
the wrong one is a 400 on every turn.
