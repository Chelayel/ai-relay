# AI Relay

**Use your company's Gemini — Vertex AI behind an Apigee gateway — as a model
inside GitHub Copilot.**

AI Relay signs in to the gateway with your client credentials and runs a small
local server that Copilot connects to like any other model provider. Copilot
keeps doing what it does (chat, agent mode, editing, running the terminal);
the model answering is your Gemini, billed to your Google Cloud project. The
gateway secret never leaves your machine.

It works with a plain Gemini API key or a standard Vertex project too.

## Quick start

**1. Install** — no Java needed, every download carries its own runtime.

```bash
brew install chelayel/tap/airelay                                                   # macOS
curl -fsSL https://raw.githubusercontent.com/Chelayel/ai-relay/main/packaging/install.sh | sh   # macOS / Linux
irm https://raw.githubusercontent.com/Chelayel/ai-relay/main/packaging/install.ps1 | iex        # Windows (PowerShell)
```

Installers (.pkg, .msi, .deb), Scoop, upgrading and removing: [docs/install.md](docs/install.md).

**2. Connect to Gemini**

```bash
airelay gemini setup
```

Pick **Vertex via Apigee** and enter the gateway host, the OAuth token URL,
your client id and secret, and the model ids the gateway publishes. It saves
them to `~/.airelay/config.properties` (owner-only) and offers a live test.

**3. Start the model server, now and at every login**

```bash
airelay serve install
```

It prints exactly what to enter in Copilot:

```
Add to Copilot (Manage Models → Custom Endpoint / OpenAI-compatible) — one entry per model:
  Deployment URL     http://localhost:11435/v1/chat/completions
  API key            air-7f06…
  Model ID          Name                        Tools  Vision  Max input  Max output
  gemini-3.8-flash  Gemini 3.8 Flash            yes    yes       1048576       65536
```

`airelay serve status` shows it again at any time; `airelay serve stop` and
`airelay serve uninstall` undo it.

## Connect GitHub Copilot (or JetBrains Air)

| Where | How |
| --- | --- |
| **VS Code and JetBrains** | In Copilot Chat's model picker: *Manage Models → Add Models → Custom Endpoint*. Enter the deployment URL, API key and model ids printed above — one entry per model. |
| **VS Code, shortcut** (1.104+) | With the **AI Relay** extension installed ([Marketplace](https://marketplace.visualstudio.com/items?itemName=chelayel.ai-relay)), the models are already in the picker under *Manage Models* — nothing to paste. |
| **Copilot CLI** | Add a BYOK provider of type `openai` with base URL `http://localhost:11435/v1` and the API key. |
| **JetBrains Air** | Through an ACP agent that takes a custom endpoint — see [JetBrains Air](#jetbrains-air) below. |

Then pick a Gemini model in Copilot's model picker and use Copilot as usual.

> **Your Copilot plan must allow outside models.** On Copilot Business and
> Enterprise an administrator can turn "bring your own key" off, and then
> these options do not appear.

The picker offers exactly the models your gateway publishes (the *Apigee
models* setting); adding one in the settings shows it without a restart. With
`gemini.prices` set, the server's log shows what each request cost. Every
detail — what is translated, security, the Ollama route, LiteLLM:
[docs/serve.md](docs/serve.md).

### JetBrains Air

Air runs coding agents over ACP rather than taking a model endpoint, so the
Gemini models reach it through an agent that does take one — [OpenCode](https://opencode.ai):
Air → OpenCode → `airelay serve` → your gateway.

1. Install OpenCode and point it at `airelay serve` — `~/.config/opencode/opencode.json`
   (one entry under `models` per gateway model; the key is the one `airelay serve` printed):

   ```json
   {
     "$schema": "https://opencode.ai/config.json",
     "provider": {
       "airelay": {
         "npm": "@ai-sdk/openai-compatible",
         "name": "AI Relay (Gemini)",
         "options": {
           "baseURL": "http://localhost:11435/v1",
           "apiKey": "air-…"
         },
         "models": {
           "gemini-3.8-flash": { "name": "Gemini 3.8 Flash", "limit": { "context": 1048576, "output": 65536 } }
         }
       }
     }
   }
   ```

2. In Air, open the agent selector → **Add ACP Agent**, and add OpenCode to the
   `acp.json` that opens (inside its existing `agent_servers` object):

   ```json
   {
     "agent_servers": {
       "OpenCode (Gemini)": {
         "command": "opencode",
         "args": ["acp"]
       }
     }
   }
   ```

   If Air cannot find `opencode`, use the full path that `which opencode` prints.

3. Pick **OpenCode (Gemini)** in Air's agent selector, and **AI Relay (Gemini) ›
   Gemini 3.8 Flash** as its model (`/models` in OpenCode switches it).

## In your IDE

AI Relay also has its own chat panel — Gemini first, Microsoft 365 Copilot
second, Claude Code as a bonus — the same agents as the terminal:

| IDE | Get it |
| --- | --- |
| **JetBrains** (IntelliJ IDEA, PyCharm, WebStorm, GoLand, Rider…) | [JetBrains Marketplace](https://plugins.jetbrains.com/search?search=AI%20Relay): *Settings → Plugins → Marketplace*, search **AI Relay**. The CLI is bundled. [Details](intellij-plugin/README.md) |
| **VS Code** | [Visual Studio Marketplace](https://marketplace.visualstudio.com/items?itemName=chelayel.ai-relay): search **AI Relay**. Also adds your Gemini models to Copilot (above). [Details](vscode-extension/README.md) |

Both settings pages write the same `~/.airelay/config.properties`, so one setup
serves the CLI, both IDEs and `airelay serve`.

## In the terminal

AI Relay is also a coding agent of its own over the repo you start it in —
the same agents as the IDE panel:

| Agent | What answers |
| --- | --- |
| `airelay gemini` | your Gemini connection — Apigee, Vertex or an API key (set up in step 2) |
| `airelay copilot` | your signed-in **Microsoft 365 Copilot** web session, driven in a browser tab — no API key, your SSO ([setup](docs/copilot-web.md)) |
| `airelay` | asks which agent, and which folder |

`airelay gemini "fix the failing test"` runs one task and exits; without a
prompt it is an interactive session. Options, keys and configuration:
[docs/cli.md](docs/cli.md).

**Bonus:** if you already use Claude Code, `airelay claude` drives it through
the same prompt and panel.

## More

- [docs/serve.md](docs/serve.md) — `airelay serve` in full
- [docs/install.md](docs/install.md) — install routes, upgrading, removing, corporate proxies, building from source
- [docs/cli.md](docs/cli.md) — the terminal agents
- [docs/copilot-web.md](docs/copilot-web.md) — the Copilot web backend
- [docs/protocol.md](docs/protocol.md) — the JSON protocol the IDE plugins speak
