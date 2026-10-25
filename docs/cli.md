# The command-line agents

[← README](../README.md)

`airelay gemini` and `airelay copilot` — and, as a bonus, `airelay claude` — are coding agents in the terminal: they read, edit and run things in the repo you start them in, plus any folder added with `--add-dir`.

## Usage

```bash
airelay gemini [options] [prompt]
airelay copilot [options] [prompt]
airelay claude [options] [prompt]    # bonus: drives a local Claude Code
```

- **With a prompt** → runs it once and exits (one-shot).
- **Without** → starts an interactive session (`/exit` to quit).

## Common options

| Option | Meaning |
| --- | --- |
| `-C, --dir PATH` | Working directory / repo root (default: current dir). |
| `--add-dir PATH` | Extra directory the agent may read/search (repeatable). |
| `-m, --model NAME` | Model id. Gemini defaults to `gemini-3.8-flash`; `airelay gemini models` lists the rest. |
| `--permission-mode readOnly\|ask\|acceptEdits\|bypass` | How freely tools run. `readOnly` reads and searches the code (and the web) to answer, and never edits a file, runs a command or calls an MCP tool. |
| `--read-only` | Alias for `--permission-mode readOnly`; `/mode search` switches to it mid-session. |
| `--yolo` | Alias for `--permission-mode bypass`. |
| `--ask` | Chat only, no tools at all (gemini, copilot). `--read-only` is usually what you want: it can still look. |

## gemini-only

| Option | Meaning |
| --- | --- |
| `--mode gemini-api\|vertex\|apigee` | Connection mode (default `gemini-api`, or `AIRELAY_GEMINI_MODE`). |
| `airelay gemini models` | List what this connection can call — live from the API in `gemini-api` mode. |

Vertex spells some models without the `-preview` suffix the Gemini API still uses
(`gemini-3.1-pro` vs `gemini-3.1-pro-preview`); the id is translated for whichever
mode is active, and a model Google has retired falls back to the current default
instead of 404-ing every turn.

## copilot-only

| Command | Meaning |
| --- | --- |
| `airelay copilot setup` | Capture your signed-in session (opens a browser). |
| `airelay copilot login` | Re-capture it after the browser session expires. |
| `airelay copilot models` | List the models the capture can switch between. |
| `airelay copilot diagnose` | Work out which field of the reply holds the answer. |
| `airelay copilot test` | Replay the capture once, to check it still works. |
| `airelay copilot reset` | Clear the captured session. |

`-m NAME` picks a model for the run; `/model NAME` switches mid-session.

## claude-only (bonus)

| Option | Meaning |
| --- | --- |
| `--agent NAME` | Run as a named `claude` sub-agent. |
| `--disallow TOOL` | Disallow a tool (repeatable). |

## Examples

```bash
# Interactive Gemini via Vertex, with an extra shared library folder in context:
airelay gemini --mode vertex --add-dir ../shared-libs

# Read-only code Q&A, no tools:
airelay gemini --ask "where is the SSE stream parsed?"

# Copilot, on a specific model from the site's picker:
airelay copilot -m claude-opus-5 "explain the agentic loop in gemini/agent"

# Bonus: one-shot with a locally installed Claude Code:
airelay claude "add a --version flag and update the README"
```


## At the prompt

| Key | Does |
| --- | --- |
| **Enter** | send |
| **Ctrl+J** | new line — works in every terminal |
| **Alt/Option+Enter** | new line (macOS Terminal: enable *Use Option as Meta key*) |
| a line ending in `\` then Enter | new line |
| **Ctrl+Enter**, **Shift+Enter** | new line, *where the terminal reports them as their own key*: Windows Terminal does, and so do terminals that honour xterm's `modifyOtherKeys` request or are set to the CSI-u key protocol (xterm, iTerm2 and others). Everywhere else they send the same byte as plain Enter and no program can tell them apart — macOS Terminal is one such. Use Ctrl+J there. |
| **↑ / ↓**, **Ctrl+R** | earlier messages / search them (`~/.airelay/history`) |
| **Ctrl+C** | during a turn: stop it, at once. On a line with text: clear it. On an empty line, twice: exit. |

Pasting several lines inserts them as one message. What you type while the
agent is working is held and appears at the next prompt.

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

**A gateway with its own path.** Vertex requests go to
`https://<endpoint>/<version>/projects/<project>/locations/<location>/publishers/google/models/<model>:streamGenerateContent`.
A path prefix can go in the endpoint (`my-gw.example.com/genai`);
`AIRELAY_VERTEX_API_VERSION` (`vertex.api.version`, default `v1`) changes the
version; `AIRELAY_VERTEX_BASE_URL` (`vertex.base.url`) replaces everything
before `/models` and may use `{project}`, `{location}` and `{version}`. The
setup wizard and both IDE settings pages ask for both.

## How it works

- `claude/ClaudeAgent` — keeps one long-lived `claude --print --input-format
  stream-json --output-format stream-json` process alive across turns (keeps the
  prompt cache warm) and renders its stream.
- `gemini/api/*` — `AuthProvider` resolves the credential per mode; `GeminiClient`
  makes one streaming `streamGenerateContent` call and parses the SSE stream.
- `serve/*` — `airelay serve`: an OpenAI- and Ollama-compatible server over the
  Gemini connection, for GitHub Copilot and other bring-your-own-model clients
  ([docs/serve.md](docs/serve.md)).
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
