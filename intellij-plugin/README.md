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
