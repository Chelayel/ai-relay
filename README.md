# AI Relay

Claude, Gemini and Copilot as **command-line coding agents**, in one tool. Same
prompt, same streaming transcript, same tools — the backends differ only in *how
they connect*:

| Agent | Connection |
| --- | --- |
| `airelay claude` | Drives the local **`claude` CLI** using whatever it is already logged in with (automatic auth — no keys handled here). |
| `airelay gemini` | Talks the Gemini REST API directly in one of three modes: **Gemini API** (key), **Vertex AI** (gcloud token), or **Vertex via Apigee** (OAuth gateway). |
| `airelay copilot` | Uses your own signed-in **Copilot web session** — SSO and all — either by driving the page in a real browser, or by replaying one captured request. Same account, same conversation, same **model picker** as the website. |

Unlike the JetBrains "Relay" plugins this is descended from, the CLI agent sees
your **whole repo** (the directory you launch it in), plus any extra folders you
pass with `--add-dir`.

## Build

Requires JDK 21. The Gradle wrapper is included.

```bash
./gradlew installDist
# launcher at build/install/airelay/bin/airelay
```

Put it on your PATH:

```bash
export PATH="$PWD/build/install/airelay/bin:$PATH"
```

## Usage

```bash
airelay claude [options] [prompt]
airelay gemini [options] [prompt]
airelay copilot [options] [prompt]
```

- **With a prompt** → runs it once and exits (one-shot).
- **Without** → starts an interactive session (`/exit` to quit).

### Common options

| Option | Meaning |
| --- | --- |
| `-C, --dir PATH` | Working directory / repo root (default: current dir). |
| `--add-dir PATH` | Extra directory the agent may read/search (repeatable). |
| `-m, --model NAME` | Model id. |
| `--permission-mode ask\|acceptEdits\|bypass` | How freely tools run. |
| `--yolo` | Alias for `--permission-mode bypass`. |
| `--ask` | Read-only Q&A, no tools (gemini, copilot). |

### gemini-only

| Option | Meaning |
| --- | --- |
| `--mode gemini-api\|vertex\|apigee` | Connection mode (default `gemini-api`, or `AIRELAY_GEMINI_MODE`). |

### claude-only

| Option | Meaning |
| --- | --- |
| `--agent NAME` | Run as a named `claude` sub-agent. |
| `--disallow TOOL` | Disallow a tool (repeatable). |

### copilot-only

| Command | Meaning |
| --- | --- |
| `airelay copilot setup` | Capture your signed-in session (opens a browser). |
| `airelay copilot login` | Re-capture it after the browser session expires. |
| `airelay copilot models` | List the models the capture can switch between. |
| `airelay copilot diagnose` | Work out which field of the reply holds the answer. |
| `airelay copilot test` | Replay the capture once, to check it still works. |
| `airelay copilot reset` | Clear the captured session. |

`-m NAME` picks a model for the run; `/model NAME` switches mid-session.

### Examples

```bash
# One-shot over the current repo, Claude:
airelay claude "add a --version flag and update the README"

# Interactive Gemini via Vertex, with an extra shared library folder in context:
airelay gemini --mode vertex --add-dir ../shared-libs

# Read-only code Q&A, no tools:
airelay gemini --ask "where is the SSE stream parsed?"

# Copilot, on a specific model from the site's picker:
airelay copilot -m claude-opus-5 "explain the agentic loop in gemini/agent"
```

## Gemini configuration

Claude needs no configuration here (it reuses the `claude` CLI's own login).

The easiest way to configure Gemini is the interactive wizard — it mirrors the
Gemini Relay settings form, asking only for the fields the chosen mode needs and
saving them to `~/.airelay/config.properties` (owner-only, `0600`):

```bash
airelay gemini setup     # pick mode, enter credentials, optional live test
airelay gemini reset     # clear saved credentials
```

Running `airelay gemini` with no config also offers to launch setup. Inside a
session, `/setup` and `/reset` do the same.

Alternatively, set **environment variables** (which override the file). See
`config.properties.sample`.

| Mode | Required |
| --- | --- |
| `gemini-api` | `AIRELAY_GEMINI_API_KEY` (or `GEMINI_API_KEY`) |
| `vertex` | `AIRELAY_VERTEX_PROJECT`; optional `AIRELAY_VERTEX_LOCATION` (default `us-central1`), `AIRELAY_GCLOUD_PATH`. Uses `gcloud auth print-access-token`. |
| `apigee` | `AIRELAY_VERTEX_PROJECT`, `AIRELAY_VERTEX_ENDPOINT` (gateway host), `AIRELAY_APIGEE_TOKEN_URL`, `AIRELAY_APIGEE_CLIENT_ID`, `AIRELAY_APIGEE_CLIENT_SECRET` |

Other keys: `AIRELAY_GEMINI_MODEL`, `AIRELAY_GEMINI_MODE`, `AIRELAY_COMMAND_TIMEOUT_SECONDS`.

## Copilot configuration

Copilot has no API key and no separate login: you are already signed in to the
website through your organisation's SSO, and this backend **reuses that session**.
There are two ways it can do that.

### Browser mode — for M365 Copilot

```bash
airelay copilot setup --browser
```

**Use this for `m365.cloud.microsoft`.** That Copilot streams its chat over a
WebSocket, so there is no request to replay — the reply never travels over one.
Browser mode instead keeps a Copilot tab open and uses it the way you would:
it types your prompt into the message box, presses Enter, and reads the answer
off the frames the page's own WebSocket receives.

Setup stores nothing but a URL — no session token, because the browser keeps it,
and nothing to re-capture when it expires.

- A browser window opens on your first turn; sign in there if it asks.
- **Pick the model in that window** — it applies to every turn. `-m` and
  `/model` don't apply here; the picker in the page is the picker.
- Leave the window open while you work.

Copilot is told the working directory and given a listing of the project, then
works like a coding agent: read the relevant files, change them, run the build or
tests, keep going until the task is done. A turn looks like this:

```
⚙ readFile src/Greet.kt
  ↳ read 1 line (33 chars)
⚙ editFile src/Greet.kt
  Edited src/Greet.kt (1 occurrence)
  - "hi "
  + "hello "
⚙ runCommand grep -c hello src/Greet.kt
  exit 0
Changed the greeting in src/Greet.kt and verified it.
```

A turn does not end just because Copilot stopped talking. Three ways a chat
model hands back half-finished work are each pushed back on once:

| It does this | The loop does this |
| --- | --- |
| Writes code out instead of applying it | `No tool call in that reply — asking Copilot to apply it, not describe it.` |
| Changes files but never checks them | `Changed 1 file(s) without checking — asking Copilot to verify.` |
| Asks "would you like me to…?" | `That reply asked whether to continue — telling Copilot to just do it.` |

At most three pushes per turn, so a model that genuinely is finished can finish.
Repeating the same tool call with the same arguments is refused after twice, so
a turn can't spin. Each turn closes with what it actually did —
`changed 1 file(s): src/Greet.kt; ran 1 command(s)`.

Edits go through `editFile`, which replaces an exact snippet rather than
rewriting the file — necessary here, because a chat composer caps a message at a
few kilobytes and a whole source file does not fit. A snippet that matches
nothing, or matches several places, is refused rather than guessed at. Long new
files can be built with repeated `writeFile` calls using `append`, and large
files read a piece at a time with `readFile` `offset`/`limit`.

Everything stays inside the workspace roots (`-C` sets the directory, `--add-dir`
adds more) — a path that escapes them is refused.

The answer is read off the page's own WebSocket where that is readable, and
otherwise from the newest message block on the page. Either way the reply is
stripped of any echo of your own prompt before it is used.

If a turn fails, the error names the text boxes it actually found on the page,
and what the one it typed into ended up holding — enough to pick a
`copilot.selector.input` without guessing. `copilot.debug=true` adds a line per
turn saying whether the answer was read from the socket or scraped off the page,
and how many frames were seen — the first thing to check if answers look wrong.

| Key | Meaning |
| --- | --- |
| `copilot.url` | Page to drive (default `https://m365.cloud.microsoft/chat`). |
| `copilot.selector.input` | CSS for the message box, if the automatic guess picks the wrong one. |
| `copilot.attach.port` | Attach to a browser you started with `--remote-debugging-port`. |
| `copilot.quiet.ms` | Silence that marks the end of an answer (default 2500). |
| `copilot.max.message.chars` | Message cap — a composer has a length limit an API wouldn't (default 7000). |

Copilot is a chat assistant underneath, so it will sometimes *write out* a change
rather than applying it. The tool contract is therefore restated briefly each
turn, and a reply that contains code but calls no tool gets one nudge — you'll
see `No tool call in that reply — asking Copilot to apply it, not describe it.`
Project memory (`CLAUDE.md`) is capped to a third of the message budget so it
can't crowd the contract out.

### Replay mode — for a Copilot that chats over HTTP

```bash
airelay copilot setup
```

That opens a browser on Copilot and watches it. You sign in as usual, send the
one short message it shows you, and it reads the resulting request straight off
the wire — URL, headers, cookies and body, at any size. Nothing is copied or
pasted by hand.

The browser runs against a profile in `~/.airelay/browser`, so the SSO login
sticks: re-capturing later usually needs no sign-in at all.

From that one request AI Relay derives the endpoint, the session headers, where
the prompt goes in the body, and which field the model picker writes to — so
nothing about the endpoint is hard-coded and a different tenant, or a change to
the site, is just another capture.

| Option | Meaning |
| --- | --- |
| `--url URL` | Page to open (default `https://m365.cloud.microsoft/chat`). |
| `--attach PORT` | Use a browser you started yourself with `--remote-debugging-port=PORT`, instead of launching one. |
| `--timeout SECONDS` | How long to wait for you to sign in (default 300). |
| `--file PATH` | Skip the browser and read a saved `Copy as cURL` from a file. |

`AIRELAY_BROWSER` points at a Chrome/Chromium/Edge binary if it isn't found
automatically; `AIRELAY_BROWSER_ARGS` adds flags to the launched browser.

> **Don't paste a cURL at a prompt.** A terminal truncates any single pasted
> line at about 4 KB and a Copilot request is tens of KB, so the command arrives
> silently cut in half. That is why `--file` takes a path rather than a paste,
> and why a truncated capture is now rejected outright instead of being saved
> and failing later. The browser capture avoids the problem entirely.

| Thing | How it's handled |
| --- | --- |
| **Session** | Whatever `Authorization`/`Cookie` the browser sent. It expires with the browser session; `airelay copilot login` re-captures it and keeps your model settings. |
| **Model choice** | If the captured body carried a model id, `-m NAME` and `/model NAME` write to that same field. `airelay copilot models` lists what you saved. |
| **History** | By default only the new message is sent and the Copilot conversation remembers the rest, exactly as the site works. Set `copilot.history=local` to re-send a transcript instead. |
| **Tools** | The endpoint is a chat surface with no function-calling, so the tool contract is taught in the preamble and requested as ```` ```tool ```` JSON blocks, which are hidden from the transcript and shown as `⚙ readFile` lines. |

### "Connected, but no assistant text could be found"

Two things cause this. Either the wrong request was captured — a page-state
endpoint answers with the app's store, and your message comes back as a
conversation title rather than a reply — in which case re-run `airelay copilot
setup` and pick a different request from the list. Or the right request was
captured and only the *shape* of the reply is unrecognised, because your tenant
streams its text under a field name the built-in list doesn't know. To tell
which, run:

```bash
airelay copilot diagnose
```

It replays one request, writes the whole response to
`~/.airelay/last-response.txt`, then ranks every string field in it — prose
scores above ids, URLs and enum labels — and offers to save the winner to
`copilot.text.keys`:

```
Fields that could hold the answer  (best first)
  1) spokenText  at spokenText  ·  3 chunk(s), 48 chars
     "I am Microsoft 365 Copilot, your work assistant."
  2) author      at author      ·  3 chunk(s), 7 chars
     "copilot"
```

`copilot.debug=true` echoes raw chunks on every turn if you want to watch it
live.

> **Note.** This talks to an undocumented endpoint using your own session. It can
> break whenever the site changes, and it may not be permitted by your terms of
> use — check before relying on it. The capture is a live credential: it is
> stored in `~/.airelay/config.properties`, written owner-only (`0600`), and
> `airelay copilot reset` removes it.

## How it works

- `claude/ClaudeAgent` — keeps one long-lived `claude --print --input-format
  stream-json --output-format stream-json` process alive across turns (keeps the
  prompt cache warm) and renders its stream.
- `gemini/api/*` — `AuthProvider` resolves the credential per mode; `GeminiClient`
  makes one streaming `streamGenerateContent` call and parses the SSE stream.
- `gemini/agent/*` — the agentic loop: stream a turn, run tool calls, feed
  results back, repeat.
- `copilot/api/*` — `BrowserCapture` drives a real browser over the DevTools
  Protocol (`DevTools`, a small CDP client on the JDK's own WebSocket) and reads
  the session request off the wire; `CurlImport` parses a saved cURL for the
  manual route; `BodyTemplate` re-renders the request per turn; `CopilotClient`
  replays it and `ResponseReader` pulls assistant text out of an unknown
  response shape (SSE, NDJSON, one JSON document or plain text).
- `copilot/agent/*` — the same agentic loop over a prompt-defined tool protocol.
- `agent/*` — the tools both agentic backends share, scoped to the workspace:
  `readFile` (whole or a line range), `editFile` (replace an exact snippet),
  `writeFile` (create, replace, or append), `listFiles`, `searchFiles`,
  `runCommand`.
- `cli/*` — the shared `Agent`/`Sink` abstraction, workspace scoping, and REPL.

Descended from the `claude-code-gui` (Claude Relay) and `gemini-relay` JetBrains
plugins; the connection code is ported from them, with IntelliJ dependencies
removed and tool access widened from a single project dir to the whole workspace.
