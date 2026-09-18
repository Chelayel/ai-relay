# AI Relay for VS Code

Claude, Gemini and Microsoft Copilot as **coding agents** over your project —
one chat panel, your own accounts, pick the agent from a dropdown.

| Agent | Signs in with |
| --- | --- |
| **Claude** | the [Claude Code](https://docs.anthropic.com/en/docs/claude-code) CLI you are already logged in to |
| **Gemini** | a Gemini API key, Vertex AI (gcloud), or a Vertex deployment behind Apigee |
| **Copilot** | your signed-in Microsoft Copilot web session (SSO and all) |

The agents read, search, edit and run commands across the whole workspace; every
tool call streams into the transcript, and you choose whether each one asks
first, whether file edits are applied silently, or whether everything just runs.

## Setup

1. **Install the `airelay` command** — the extension is a front-end for it and
   needs it on your machine. No Java required; see
   [Install](https://github.com/Chelayel/ai-relay#install):
   `curl -fsSL https://raw.githubusercontent.com/Chelayel/ai-relay/main/packaging/install.sh | sh`
   on macOS/Linux, `irm …/install.ps1 | iex` on Windows, or an installer from
   the releases page. If it isn't on your PATH, set `airelay.path`.
2. **Claude** needs nothing more. For **Gemini** or **Copilot**, run
   *AI Relay: Set Up an Agent* from the command palette — it opens a terminal
   with the setup wizard. The configuration is shared with the command line
   (`~/.airelay`).
3. Open the **AI Relay** view in the activity bar.

## Using it

- **Enter** sends, **Shift+Enter** adds a line, **Esc** stops a running turn.
- *Include editor selection* attaches what is selected in the active editor.
- The permission bar appears when the agent wants to run something in *ask*
  mode; *Allow* runs it once, *Always* for the rest of the conversation.
- *New* starts a fresh conversation; switching agent or permission mode does too.

## Settings

| Setting | Default | |
| --- | --- | --- |
| `airelay.path` | `airelay` | The command, or a full path to it. |
| `airelay.backend` | `claude` | Default agent for new windows. |
| `airelay.permissionMode` | `acceptEdits` | `ask`, `acceptEdits` or `bypass`. |
| `airelay.extraArgs` | — | Extra arguments for every launch, e.g. `--model NAME --no-web`. |

Independent and unofficial — not affiliated with Anthropic, Google or Microsoft.
Source and issues: [github.com/Chelayel/ai-relay](https://github.com/Chelayel/ai-relay).
