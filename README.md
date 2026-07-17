# AI Relay

Claude and Gemini as **command-line coding agents**, in one tool. Same prompt,
same streaming transcript, same tools — the two backends differ only in *how they
connect*:

| Agent | Connection |
| --- | --- |
| `airelay claude` | Drives the local **`claude` CLI** using whatever it is already logged in with (automatic auth — no keys handled here). |
| `airelay gemini` | Talks the Gemini REST API directly in one of three modes: **Gemini API** (key), **Vertex AI** (gcloud token), or **Vertex via Apigee** (OAuth gateway). |

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
| `--ask` | Read-only Q&A, no tools (gemini). |

### gemini-only

| Option | Meaning |
| --- | --- |
| `--mode gemini-api\|vertex\|apigee` | Connection mode (default `gemini-api`, or `AIRELAY_GEMINI_MODE`). |

### claude-only

| Option | Meaning |
| --- | --- |
| `--agent NAME` | Run as a named `claude` sub-agent. |
| `--disallow TOOL` | Disallow a tool (repeatable). |

### Examples

```bash
# One-shot over the current repo, Claude:
airelay claude "add a --version flag and update the README"

# Interactive Gemini via Vertex, with an extra shared library folder in context:
airelay gemini --mode vertex --add-dir ../shared-libs

# Read-only code Q&A, no tools:
airelay gemini --ask "where is the SSE stream parsed?"
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

## How it works

- `claude/ClaudeAgent` — keeps one long-lived `claude --print --input-format
  stream-json --output-format stream-json` process alive across turns (keeps the
  prompt cache warm) and renders its stream.
- `gemini/api/*` — `AuthProvider` resolves the credential per mode; `GeminiClient`
  makes one streaming `streamGenerateContent` call and parses the SSE stream.
- `gemini/agent/*` — the agentic loop: stream a turn, run tool calls
  (read/write/list/search/run, scoped to the workspace), feed results back, repeat.
- `cli/*` — the shared `Agent`/`Sink` abstraction, workspace scoping, and REPL.

Descended from the `claude-code-gui` (Claude Relay) and `gemini-relay` JetBrains
plugins; the connection code is ported from them, with IntelliJ dependencies
removed and tool access widened from a single project dir to the whole workspace.
