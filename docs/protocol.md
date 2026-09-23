# The `--json` protocol

`airelay <backend> --json [options]` runs the agent as a subprocess for an IDE.
The IntelliJ plugin and the VS Code extension are both shells around this: they
never talk to a model themselves, so the three backends behave the same in every
front-end and a fix in the CLI lands everywhere at once.

One JSON object per line. Events on **stdout**, commands on **stdin**, free
text for humans on **stderr** (a missing configuration is reported there, and
the process exits with status 1 — the shell shows that text and points at the
matching `airelay … setup` command).

## Events (stdout)

| Event | Fields | Meaning |
| --- | --- | --- |
| `ready` | `backend`, `describe`, `workspace[]`, `mcp[]`, `skills[]`, `model`, `models[]`, `agent`, `agents[]` | The agent is built; commands are accepted. `workspace` is the repo root plus every `--add-dir`; `mcp` names the configured servers (started on the first turn, which then reports their tool count as `info`); `skills` is `{name, description, source}` for every `SKILL.md` found in `.claude/skills`, `.gemini/skills`, `.airelay/skills` under a workspace root, or `~/.claude/skills`. |
| `text` | `text` | Assistant text, streamed in small pieces. Concatenate. |
| `thinking` | `text` | A thought summary, when the backend exposes one. |
| `tool_use` | `name`, `summary` | A tool call is starting. |
| `tool_result` | `text`, `isError` | Its output. |
| `info` / `error` | `text` | Status lines. |
| `file_changed` | `path`, `diff`, `revertId` | The agent changed a file: a unified diff, and the id to `revert` it. A revert is reported the same way, with its own id. |
| `usage` | `contextTokens`, `costUsd` | Token and cost accounting after a turn, from backends that report it (Claude). |
| `sessions` | `list[]` of `{id, backend, title, model, updatedAt}` | Answer to a `sessions` command: the conversations kept for this folder. |
| `replay_start` / `replay_end` | `id`, `title` / `id`, `resumed` | Bracket a resumed transcript: between them the recorded events are re-sent as they were (`user` carries the prompt); `resumed` says whether the agent took the conversation over or it was replay only. |
| `permission` | `id`, `name`, `summary`, `detail` | The agent wants to run `name`; the turn is blocked until answered. `detail` is what would happen: the command in full, an edit as `- `/`+ ` lines, a file's first lines. |
| `stopped` | `text` | The turn was cancelled; whatever the agent still says is dropped. |
| `turn_complete` | `elapsedMs`, `files[]`, `commands`, `tools` | Exactly once per `send`, however the turn ended. What the turn did: files changed, commands run, tool calls. |
| `exit` | — | The last line. |

## Commands (stdin)

| Command | Fields | Meaning |
| --- | --- | --- |
| `send` | `text`, `skills[]`, `images[]` (both optional) | Start a turn. Refused with an `error` while one is running. `skills` are names from `ready`; each one's instructions are put in front of `text`. `images` are `{name, mimeType, data}` with base64 data, from a paste or a drop; Gemini and Claude carry them, Copilot reports it cannot and sends the text alone. |
| `permission` | `id`, `decision`: `allow`, `always`, `deny` | Answer a `permission` event. |
| `cancel` | — | Stop the running turn; `stopped` then `turn_complete` follow at once. |
| `model` | `name` | Switch models for the rest of the conversation (all backends; Claude restarts its CLI on the next turn); answered with `info`. An empty name lists them. |
| `sessions` | `query` (optional) | List the conversations kept for this folder, or those whose title or transcript contains every word of `query`; answered with `sessions`. |
| `agent` | `name` (empty clears) | Adopt a persona from `agents` in `ready`: the system prompt for Gemini and Copilot, Claude's own agent by that name; answered with `info` or `error`. |
| `resume` | `id` | Replay a conversation and, when the backend can, continue it. |
| `mode` | `name`: `ask`, `acceptEdits`, `bypass` | Change the permission mode without restarting; answered with `info`, or `error` when the backend cannot. |
| `add_dir` | `path` | Let the agent see another directory from now on; answered with `info` or `error`. |
| `revert` | `id` (optional, else the last change) | Put a file back as it was before that change; answered with `file_changed` + `info`, or `error`. |
| `exit` | — | Close the agent and quit. End of input does the same. |

## Example

```
→ {"type":"send","text":"add a test for Parser"}
← {"type":"text","text":"I'll look at "}
← {"type":"text","text":"the parser first.\n"}
← {"type":"tool_use","name":"readFile","summary":"src/Parser.kt"}
← {"type":"tool_result","text":"…","isError":false}
← {"type":"permission","id":1,"name":"runCommand","summary":"./gradlew test"}
→ {"type":"permission","id":1,"decision":"allow"}
← {"type":"tool_result","text":"exit 0\nBUILD SUCCESSFUL","isError":false}
← {"type":"turn_complete"}
```

Try it without a model: `airelay demo --json`.
