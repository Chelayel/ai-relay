# AI Relay

Claude, Gemini and Copilot as **command-line coding agents**, in one tool. Same
prompt, same streaming transcript, same tools — the backends differ only in *how
they connect*:

| Agent | Connection |
| --- | --- |
| `airelay claude` | Drives the local **`claude` CLI** using whatever it is already logged in with (automatic auth — no keys handled here). |
| `airelay gemini` | Talks the Gemini REST API directly in one of three modes: **Gemini API** (key), **Vertex AI** (gcloud token), or **Vertex via Apigee** (OAuth gateway). |
| `airelay copilot` | Replays one request captured from your own signed-in **Copilot web session** — SSO and all — so the CLI uses the same account, the same conversation and the same **model picker** as the website. |

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
| `airelay copilot setup` | Capture your signed-in session (interactive). |
| `airelay copilot login` | Re-capture it after the browser session expires. |
| `airelay copilot models` | List the models the capture can switch between. |
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
Setup asks for one request copied out of your browser, and works out the rest.

```bash
airelay copilot setup
```

In the browser, before running it:

1. Open Copilot and sign in as usual.
2. Open DevTools (F12) → **Network**, clear the list.
3. Send one short message — remember **exactly** what you typed.
4. Find the request carrying it (the big POST that appears when you hit Enter).
5. Right-click → **Copy** → **Copy as cURL**, and paste that into setup.

From that one request AI Relay derives the endpoint, the session headers, where
the prompt goes in the body, and which field the model picker writes to — so
nothing about the endpoint is hard-coded and a different tenant, or a change to
the site, is just another capture.

| Thing | How it's handled |
| --- | --- |
| **Session** | Whatever `Authorization`/`Cookie` the browser sent. It expires with the browser session; `airelay copilot login` re-captures it and keeps your model settings. |
| **Model choice** | If the captured body carried a model id, `-m NAME` and `/model NAME` write to that same field. `airelay copilot models` lists what you saved. |
| **History** | By default only the new message is sent and the Copilot conversation remembers the rest, exactly as the site works. Set `copilot.history=local` to re-send a transcript instead. |
| **Tools** | The endpoint is a chat surface with no function-calling, so the tool contract is taught in the preamble and requested as ```` ```tool ```` JSON blocks, which are hidden from the transcript and shown as `⚙ readFile` lines. |

If a reply comes back with no text, AI Relay prints the head of the raw response
and the field to point `copilot.text.keys` at — the response shape is
undocumented, so that is the escape hatch when a tenant streams under a key the
built-in list doesn't know. `copilot.debug=true` echoes raw chunks every turn.

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
- `copilot/api/*` — `CurlImport` parses the captured request, `BodyTemplate`
  re-renders it per turn, `CopilotClient` replays it and pulls assistant text out
  of an unknown response shape (SSE, NDJSON or one JSON document).
- `copilot/agent/*` — the same agentic loop over a prompt-defined tool protocol.
- `agent/*` — the tools both agentic backends share (read/write/list/search/run,
  scoped to the workspace).
- `cli/*` — the shared `Agent`/`Sink` abstraction, workspace scoping, and REPL.

Descended from the `claude-code-gui` (Claude Relay) and `gemini-relay` JetBrains
plugins; the connection code is ported from them, with IntelliJ dependencies
removed and tool access widened from a single project dir to the whole workspace.
