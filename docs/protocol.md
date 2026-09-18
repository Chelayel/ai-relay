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
| `ready` | `backend`, `describe`, `workspace[]` | The agent is built; commands are accepted. |
| `text` | `text` | Assistant text, streamed in small pieces. Concatenate. |
| `thinking` | `text` | A thought summary, when the backend exposes one. |
| `tool_use` | `name`, `summary` | A tool call is starting. |
| `tool_result` | `text`, `isError` | Its output. |
| `info` / `error` | `text` | Status lines. |
| `permission` | `id`, `name`, `summary` | The agent wants to run `name`; the turn is blocked until answered. |
| `stopped` | `text` | The turn was cancelled; whatever the agent still says is dropped. |
| `turn_complete` | — | Exactly once per `send`, however the turn ended. |
| `exit` | — | The last line. |

## Commands (stdin)

| Command | Fields | Meaning |
| --- | --- | --- |
| `send` | `text` | Start a turn. Refused with an `error` while one is running. |
| `permission` | `id`, `decision`: `allow`, `always`, `deny` | Answer a `permission` event. |
| `cancel` | — | Stop the running turn; `stopped` then `turn_complete` follow at once. |
| `model` | `name` | Switch models (copilot only); answered with `info`. |
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
