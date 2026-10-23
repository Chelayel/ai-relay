# AI Relay for VS Code

## Your Gemini models in GitHub Copilot

With this extension installed, the models of your Gemini connection — a Vertex
deployment behind an Apigee gateway, a Vertex project or a Gemini API key —
appear in **GitHub Copilot Chat's model picker** (VS Code 1.104+). Copilot keeps
working as usual, agent mode included; the model answering is your Gemini.

1. Set up the connection once: *AI Relay: Set Up an Agent* → Gemini (or
   `airelay gemini setup` in a terminal).
2. In Copilot Chat's model picker choose *Manage Models* and enable the
   **AI Relay** models.

The extension starts the CLI's model server (`airelay serve`) itself, on a
free local port, and stops it with VS Code. Your Copilot plan must allow
outside models (an administrator can turn this off on Business and
Enterprise). More, including other clients: [airelay serve](https://github.com/Chelayel/ai-relay/blob/main/docs/serve.md).

## The AI Relay chat panel

Gemini and Microsoft 365 Copilot as **coding agents** over your project — one
chat panel, your own accounts, pick the agent from a dropdown.

| Agent | Signs in with |
| --- | --- |
| **Gemini** | an Apigee gateway (OAuth client), Vertex AI (gcloud), or a Gemini API key |
| **Copilot** | your signed-in Microsoft 365 Copilot web session (SSO and all) |
| **Claude** *(bonus)* | the [Claude Code](https://docs.anthropic.com/en/docs/claude-code) CLI, if you already use it |

The agents read, search, edit and run commands across the whole workspace; every
tool call streams into the transcript, and you choose whether each one asks
first, whether file edits are applied silently, or whether everything just runs.

![An agent turn: tool calls, an edit shown as a diff, a test run](https://raw.githubusercontent.com/Chelayel/ai-relay/main/docs/screenshots/agent-turn.png)

![Ask mode: a permission prompt for a command](https://raw.githubusercontent.com/Chelayel/ai-relay/main/docs/screenshots/permission.png)

![The composer: + menu, editor selection, an attached file and an image](https://raw.githubusercontent.com/Chelayel/ai-relay/main/docs/screenshots/composer.png)

![Switching between the models an Apigee gateway publishes](https://raw.githubusercontent.com/Chelayel/ai-relay/main/docs/screenshots/model-switch.png)

## Setup

1. **Have Java 21 or newer, or install the `airelay` command.** The extension bundles the CLI and runs it on a Java it finds (`JAVA_HOME`, the system's JDKs, PATH); with no Java, install the command — the extension is a front-end for it and
   needs it on your machine. No Java required; see
   [Install](https://github.com/Chelayel/ai-relay/blob/main/docs/install.md):
   `curl -fsSL https://raw.githubusercontent.com/Chelayel/ai-relay/main/packaging/install.sh | sh`
   on macOS/Linux, `irm …/install.ps1 | iex` on Windows, or an installer from
   the releases page. If it isn't on your PATH, set `airelay.path`.
2. For **Gemini**, **Copilot** or **web search**, run *AI Relay: Set Up an Agent* (command palette, or the ⚙ button
   in the panel): pick the connection, answer a few input boxes — keys are
   masked — and it is saved to `~/.airelay/config.properties`, readable only by
   you and shared with the command line and the IntelliJ plugin. Copilot's
   default is browser mode: a Chrome/Edge tab opens on the first turn and you
   sign in there.
   **Claude** (bonus) needs nothing: it uses the `claude` CLI's own login.
3. Open the **AI Relay** view in the activity bar.

## Using it

- **Enter** sends, **Shift+Enter** adds a line, **Esc** stops a running turn.
- The **+** button beside the message box attaches files or folders (they go in as paths the agent reads on demand), lists the skills found (`SKILL.md` files in `.claude/skills`, `.gemini/skills` or `.airelay/skills` in the project, or `~/.claude/skills`) to attach one to the next message, opens the MCP config the CLI reads — creating an empty one if there is none — and reaches the agent settings.
- In the message box, `/` offers the commands (`/model`, `/mode`, `/skill`, `/agent`, `/resume`, `/revert`, `/add-dir`), `@` picks a file from the workspace, `#` a skill. A message sent while the agent is busy is queued and goes out when the turn ends; an unsent draft survives a reload. Up in an empty box recalls the last message; Cmd/Ctrl+K opens the + menu.
- Runs of tool calls group under one line, open, each named as an action (Read, Edit, Run, Search) with what came back. A path in a tool row or a diff header opens the file at that line. Code fences have Copy, and Apply when the text names a file. A permission prompt shows the command or the edit it is asking about.
- The header is a coloured agent switch with a health dot; hover the meter for this turn's cost against the session's. The history list has a search box over titles and transcripts. An agent that is not set up shows a setup card instead of an error.
- Every edit the agent makes shows as a diff with a **Revert** button. The mode dropdown changes how tools run without losing the conversation.
- The **⟲** button lists earlier conversations in this folder; pick one to replay it and, for Claude and Gemini, carry on. The header also has a model picker, and for Claude a context and cost meter.
- The **+** menu also lists agent personas (`.md` files in `.claude/agents`) to adopt for the rest of the conversation.
- Drop files onto the panel, or paste an image into the message box: an image is sent as a picture (Gemini and Claude; Copilot says it cannot take one), a text file goes in with its contents, and a file dragged from the project tree is attached by path.
- The chip under the message box shows the current file, or the selected lines when there is a selection, and follows the editor as you move around. It is attached to the next message; click it to leave it out.
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
