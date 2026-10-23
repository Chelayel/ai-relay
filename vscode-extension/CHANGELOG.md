# Changelog

## 2.5.0

The agent explores the project the way Copilot's does: a new findFiles tool finds files by name or glob, Gemini is given the working directory and a map of the project, and it is told to read the files a question involves (callers, implementations, tests, config) instead of answering from the one file you are looking at. The file in the editor is now passed as a starting point to read from.
## 2.4.5

airelay serve: the input limit it reports (banner, model list, VS Code model picker) is the context window less the output budget — 983,040 for Gemini, not 1,048,576 — because VS Code adds input and output and takes the sum as the window. The README shows the chatLanguageModels.json VS Code keeps for a Custom Endpoint.

## 2.4.4

GitHub Copilot connects to airelay serve the same way in VS Code and JetBrains: Manage Models → Add Models → Custom Endpoint, with the values the server prints. The docs, the banner and the plugin description said JetBrains needed the Ollama option and --no-token; it does not. Ollama stays available for clients that offer nothing else.

## 2.4.3

Gemini first: the agent menus, the setup and the descriptions now lead with Gemini (an Apigee gateway, Vertex or an API key) and its use as a model in GitHub Copilot, then Microsoft 365 Copilot; Claude Code stays available as a bonus, last in the agent dropdown. The README is short and starts with the GitHub Copilot setup; the details moved to the docs folder.

## 2.4.2

airelay serve: --background works on Homebrew and the other packaged installs (it tried to run a java the packaged runtime does not have); addresses are shown as localhost, and the server listens on IPv6 too so clients that try ::1 first connect; `airelay serve install` is listed first, as the way to keep it running.

## 2.4.1

airelay serve: a client that asks for a "deployment URL" works whether it uses the URL as given or appends /chat/completions itself, and Azure-style deployment paths take the model from the URL. It prints what Copilot's custom-model form asks for (deployment URL, API key, and per model its id, name, tool and vision support and token limits), runs in the background (`airelay serve --background`, `status`, `stop`) and can start at every login (`airelay serve install`, `uninstall`: launchd, systemd --user or the Startup folder). VS Code: an installed `airelay` older than the extension no longer wins over the newer CLI bundled in it, which is why the model dropdown showed one model for some; the panel says when the CLI in use is out of date.

## 2.4.0

New: `airelay serve` — your Gemini connection (including an Apigee gateway) as a local model server for GitHub Copilot and other bring-your-own-model clients. In VS Code (1.104+) the AI Relay models appear in Copilot's model picker with nothing to configure; Copilot CLI and JetBrains (Ollama option) connect to it by URL. Copilot runs the agent, AI Relay forwards the model calls and keeps the credentials. See docs/serve.md.

## 2.3.2

The open file is attached reliably: Send attaches the file the chip shows, rather than looking up the active editor again (which often came back empty once the chat had focus). Clicking into the Output panel, a console, a diff or an image no longer clears it, and a chip clicked off comes back on for the next file.

## 2.3.1

IntelliJ only: Opening a file from the chat (a diff header, a tool row, the MCP config) no longer uses an API IntelliJ 2026.3 deprecates. Nothing changes in VS Code.

## 2.3.0

Gemini shows what each turn cost when you give it the rates (`gemini.prices`, USD per 1M tokens per model — e.g. an Apigee gateway's price list); the cost lands on the ✓ line under the turn, the session total in the header. The chat panel shows tool calls instead of hiding them under a collapsed line, names them as actions (Read, Edit, Run, Search, Fetch; an MCP tool as server · tool) and says what came back (3 lines, exit 0, the error). The per-round token line and the repeated MCP notice are gone from the transcript.

## 2.2.2

Gemini through Apigee: the model picker lists only the models the gateway publishes, without the default model added next to them. Other connections: Gemini 4 Argon and 3.8 Flash are in the picker, and 3.8 Flash is the new default (3.7 Flash is deprecated). A Gemini turn ends as soon as the answer is complete, instead of staying busy until the connection closes (a gateway could hold it open for minutes).

## 2.2.1

Gemini through Apigee: the model picker lists every model the gateway publishes (`apigee.agents`, asked for by the setup wizard), not just the default one.

## 2.2.0

Read-only mode: pick **Read only** in the mode menu (or type `/mode search`) and the agent reads and searches the code to answer, but never edits a file or runs a command. Copilot in the browser: a reply that is still rendering is waited for instead of being reported as missing, and a retried send keeps the conversation (it used to open a new chat that had lost the project context).

## 2.1.2

An MCP server that exits at startup now fails at once with its own error message, not after a minute or with only an exit code. A server's own requests (such as a ping) are no longer mistaken for replies, and a server that dies right after connecting is restarted on the next call.

## 2.1.1

The bundled CLI is actually used now: the path setting defaulted to the literal `airelay`, which skipped the bundled copy on every machine without one on PATH. A missing CLI no longer leaves the panel stuck on busy. A `.cmd` launcher on Windows starts (current Node needs a shell for batch files), and a folder in the path setting is searched for every layout the installers produce. "Apply to file" writes only inside the workspace. Conversations are titled by what you typed, not by "Current file: …". Links with a quote in them can no longer inject markup. A second message typed during a turn is added to the queued one, and both go as one message when the turn ends, instead of the first being dropped.

## 2.1.0

A round of bug fixes. The bundled CLI is chosen again on machines whose `java -version` prints to stderr (all of them), so the extension no longer needs a separate `airelay` on PATH. Stop stops the turn even with a draft in the box. A diff replayed from an earlier session no longer offers a Revert it cannot do. Links in the transcript open in the browser again. Windows paths with a drive letter open in the editor. A restarted agent no longer leaves the panel stuck on "busy", and events from a replaced process no longer reach the page.

## 2.0.2

Version kept in step with the CLI; no extension changes.

## 2.0.1

Enter clears the composer again; a message sent while the agent was starting came back in the box.

## 2.0.0

Smaller Gemini requests on long jobs; one shared browser for Copilot across projects; after 30 idle minutes the agent releases its Claude process, browser and MCP servers until the next message; a leaner JVM.

## 1.9.1

The editor context names a file the agent can open, including files in modules outside the project directory. From this release the plugin and the extension ship at the CLI's version.

## 1.9.0

Copilot is no longer given a system prompt; `copilot.system.prompt` adds one.

## 1.8.6

Bundles the CLI at its release version; 1.8.5 bundled it labelled 1.8.3.

## 1.8.5

The CLI version shows in the header tooltip and when the health dot is clicked.

## 1.8.4

The connection line in the header wraps instead of being cut off.

## 1.8.3

Version kept in step with the CLI; no extension changes.

## 1.8.2

The CLI is bundled: with no `airelay` command installed, the extension runs
the bundled one on a Java 21+ it finds (JAVA_HOME, the system's JDKs, PATH),
the way the IntelliJ plugin does. An installed command is searched for in the
usual install places, not only PATH. With neither, a notification offers to
run the installer in a terminal or to set `airelay.path`.

## 1.8.1

Gemini is the default agent.

## 1.8.0

The agent picker is a dropdown again, beside the model picker. On an Apigee
gateway the model picker offers only the configured model.

## 1.7.0

Slash commands, @file and #skill autocomplete in the composer; queued
messages and draft memory; grouped tool calls; open-at-line from diffs and
tool rows; Copy and Apply on code fences; permission prompts show the command
or edit; a coloured agent switch with a health dot; history search; a setup
card for an unconfigured agent; light-theme accents.

## 1.6.0

Edits shown as diffs with Revert; earlier conversations listed and
resumable; a model picker and, for Claude, a context and cost meter; agent
personas from .claude/agents; the mode dropdown no longer restarts the
conversation; a clock on the running tool and a summary line per turn.

## 1.5.0

Drop files onto the panel or paste an image into the message box: images go
to Gemini and Claude as pictures, text files go in with their contents,
explorer drags attach by path.

## 1.4.0

A + menu in the composer: attach files, attach a skill (SKILL.md files in
.claude/skills and friends), open the MCP config, settings. The panel is
coloured by the agent in use and always shows the one actually running. The
demo backend is gone from the picker.

## 1.3.0

The header shows the workspace folders and the configured MCP servers; the
context chip keeps the file name whole.

## 1.2.0

Live editor context: the chip under the message box follows the current file
and selection and is attached automatically; click it to leave it out.

## 1.0.0

First release: chat view over the `airelay` command with Claude, Gemini and
Copilot backends, streaming tool activity, permission prompts, editor selection
as context.
