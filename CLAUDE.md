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
- `agent/Tools` — the workspace tools, shared by the Gemini and Copilot agents:
  `readFile` (whole or a line range), `editFile` (exact-snippet replace),
  `writeFile` (create/replace/append), `listFiles`, `searchFiles`, `runCommand`.
  **`editFile` is the one that matters for Copilot**: a chat composer caps a
  message at a few KB, so rewriting a whole file is out of reach for anything
  real. An ambiguous or absent snippet is an error, never a guess — editing the
  wrong occurrence silently is worse than being asked for more context. `agent/ToolSpec` is the backend-neutral
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
  **`copilot/api/CodeSpans` is what keeps that subtraction off the tool calls**:
  the prompt lists the project's files, so the path in a `readFile` call is an
  echo of a line we sent — it was cut out of the call, the tool ran with nothing,
  every read answered "No such file", and Copilot concluded it had no access to
  the project. Fenced blocks and balanced JSON objects are masked in *both* texts
  against one content-keyed token set before the subtraction and restored after:
  our own echoed example still masks to the token we did and is still removed,
  while a call Copilot wrote itself has no counterpart and survives whole. Braces
  matter as much as fences, because a rendered code block read back has no
  backticks left around it.
  **Attach and `Network.enable` before navigating**: CDP reports frames only for
  sockets created while the domain is enabled, so a page loaded first streams
  its whole conversation past unseen. When a socket exists, only it votes on
  whether a turn is progressing — a chat page echoes the message it was just
  given, and counting that as the reply ends the turn seconds after Enter and
  returns our own prompt as the answer.
  Finding the composer is the only DOM dependency, and it's overridable with
  `copilot.selector.input`. Re-find it **before every message**, not once per
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
