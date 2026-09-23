# AI Relay for JetBrains IDEs

Claude, Gemini and Microsoft Copilot as **coding agents** in a tool window of
IntelliJ IDEA, PyCharm, WebStorm, GoLand, Rider and the rest of the family.
Same panel, pick the agent from a dropdown, your own accounts:

| Agent | Signs in with |
| --- | --- |
| **Claude** | the [Claude Code](https://docs.anthropic.com/en/docs/claude-code) CLI you are already logged in to |
| **Gemini** | a Gemini API key, Vertex AI (gcloud), or a Vertex deployment behind Apigee |
| **Copilot** | your signed-in Microsoft Copilot web session (SSO and all) |

The plugin is a front-end for the [`airelay`](../README.md) command line: the
CLI's jars are bundled inside it and run as a subprocess on the IDE's own Java,
so there is nothing else to install — not a JDK, not the CLI. Connection
settings live in `~/.airelay`, shared with the command line.

## Install

- From disk: **Settings → Plugins → ⚙ → Install Plugin from Disk…** and pick
  `ai-relay-intellij-<version>.zip` from
  [Releases](https://github.com/Chelayel/ai-relay/releases).
- From JetBrains Marketplace: search for **AI Relay** (once published).

Then open the **AI Relay** tool window on the right.

## Setting up an agent

- **Claude** needs nothing: the plugin uses the `claude` CLI's own login.
- **Gemini** and **Copilot**: **Settings → Tools → AI Relay** (or the ⚙ button
  in the panel). One form: Gemini mode and key (or Vertex project, or the
  Apigee gateway and its OAuth client), the Copilot page URL, web search. *Test
  Gemini* / *Test Copilot* / *Test web access* call the connection live with
  what you typed. Saved to `~/.airelay/config.properties`, readable only by
  you, and picked up by the `airelay` command and the VS Code extension too.
- **Copilot** defaults to browser mode — the one for Microsoft 365 Copilot: a
  Chrome/Edge tab opens on the first turn, you sign in there, and that is all.

## Using it

- **Enter** sends, **Shift+Enter** adds a line, **Esc** stops a running turn.
- The **+** button beside the message box attaches files or folders (they go in as paths the agent reads on demand), lists the skills found (`SKILL.md` files in `.claude/skills`, `.gemini/skills` or `.airelay/skills` in the project, or `~/.claude/skills`) to attach one to the next message, opens the MCP config the CLI reads — creating an empty one if there is none — and reaches the agent settings.
- In the message box, `/` offers the commands (`/model`, `/mode`, `/skill`, `/agent`, `/resume`, `/revert`, `/add-dir`), `@` picks a file from the workspace, `#` a skill. A message sent while the agent is busy is queued and goes out when the turn ends; an unsent draft survives a reload. Up in an empty box recalls the last message; Cmd/Ctrl+K opens the + menu.
- Runs of tool calls fold into one expandable line. A path in a tool row or a diff header opens the file at that line. Code fences have Copy, and Apply when the text names a file. A permission prompt shows the command or the edit it is asking about.
- The header is a coloured agent switch with a health dot; hover the meter for this turn's cost against the session's. The history list has a search box over titles and transcripts. An agent that is not set up shows a setup card instead of an error.
- Every edit the agent makes shows as a diff with a **Revert** button. The mode dropdown changes how tools run without losing the conversation.
- The **⟲** button lists earlier conversations in this folder; pick one to replay it and, for Claude and Gemini, carry on. The header also has a model picker, and for Claude a context and cost meter.
- The **+** menu also lists agent personas (`.md` files in `.claude/agents`) to adopt for the rest of the conversation.
- Drop files onto the panel, or paste an image into the message box: an image is sent as a picture (Gemini and Claude; Copilot says it cannot take one), a text file goes in with its contents, and a file dragged from the project tree is attached by path.
- The chip under the message box shows the current file, or the selected lines when there is a selection, and follows the editor as you move around. It is attached to the next message; click it to leave it out.
- The permission bar appears when the agent wants to run something in *Ask*
  mode; *Allow* runs it once, *Always* for the rest of the conversation.
- Files the agent writes are refreshed in the IDE after every tool call.
- *New* starts a fresh conversation; switching agent or permission mode does too.

## Requirements

- IntelliJ platform 2024.2 or newer.
- The embedded browser (JCEF), which every JetBrains IDE ships. On a build
  without it the tool window says so and points at the command line.

## Building

```bash
cd intellij-plugin
./gradlew buildPlugin        # build/distributions/ai-relay-intellij-<version>.zip
./gradlew runIde             # a sandbox IDE with the plugin installed
./gradlew verifyPlugin       # JetBrains' compatibility checks
```

The CLI is pulled in from the repository root by a Gradle composite build; no
separate publish step.

Independent and unofficial — not affiliated with Anthropic, Google, Microsoft or JetBrains.
