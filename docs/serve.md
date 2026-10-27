# `airelay serve` — your Gemini connection, for GitHub Copilot

`airelay serve` runs a model server on your machine that forwards to the
Gemini connection AI Relay is already set up with — Gemini API key, Vertex, or
a Vertex deployment behind an Apigee gateway. Clients that let you bring your
own model connect to it: GitHub Copilot in VS Code and JetBrains IDEs (Custom
Endpoint), and Copilot CLI. It also speaks Ollama's API, for clients that offer
nothing else.

```
Copilot ──OpenAI / Ollama request──▶ airelay serve (localhost) ──▶ Apigee / Vertex / Gemini API
        ◀──streamed answer, tool calls──
```

Copilot keeps doing the agent work — reading files, editing, running the
terminal. AI Relay only forwards each model call and holds the credentials, so
the Apigee client secret never leaves it.

## Before you start

- **Your Copilot plan must allow outside models.** On Copilot Business and
  Enterprise an administrator can turn "bring your own key" off; then none of
  the routes below appear in Copilot. VS Code's documentation also says models
  added by an extension are currently available on individual plans.
- **Gemini must be set up in AI Relay** (`airelay gemini setup`, or the IDE
  settings). `airelay serve` refuses to start otherwise and says what is missing.

## Start it

```bash
airelay serve install      # now, and at every login — the usual way
airelay serve              # or: in this terminal, until Ctrl-C
```

```
AI Relay is serving Gemini (Vertex via Apigee (OAuth gateway)) on http://localhost:11435
  OpenAI-compatible  http://localhost:11435/v1   API key: air-7f06…
  models             gemini-3.8-flash, gemini-4-argon
```

The API key is made the first time and kept in `~/.airelay/config.properties`
(`serve.token`), so a client set up with it keeps working. Options:

| Option | |
| --- | --- |
| `--port N` | Port on localhost. Default 11435, or `serve.port`. |
| `--mode M` | `gemini-api`, `vertex` or `apigee`, if not the configured one. |
| `--no-token` | Accept requests without the key — only for clients that connect as "Ollama", which send none. Also `serve.token=off`. |

### Keep it running

`airelay serve` holds the terminal it runs in. Two ways to free it:

| Command | |
| --- | --- |
| **`airelay serve install`** | **Recommended.** Start it now and at every login — a LaunchAgent on macOS, a systemd user service on Linux, the Startup folder on Windows. Options given (`--port`, `--no-token`, `--mode`) are kept. |
| `airelay serve --background` | Start it detached, just until it is stopped or you log out. It survives closing the terminal; output goes to `~/.airelay/serve.log` (without the API key). |
| `airelay serve status` | Whether it is running, where, whether it starts at login, and the **Add to Copilot** values. |
| `airelay serve stop` | Stop it (an installed one comes back at the next login). |
| `airelay serve uninstall` | Stop starting it at login, and stop it. |

The login entry runs the `airelay` command on your PATH, so upgrading AI Relay
does not break it. On Windows it runs Java directly from an argument file:
run `airelay serve install` again after upgrading there.

It listens on this machine only (127.0.0.1 and ::1, so `localhost` works whichever a client tries first), refuses requests from web pages (any request
with an `Origin` header, or addressed to a host name other than localhost),
and requires the key unless started with `--no-token`.

## Choosing the model

The client's model picker lists what AI Relay's own picker lists: on Apigee,
exactly the ids in `apigee.agents` (the **Apigee models** setting); otherwise
your `gemini.model` list and the current Gemini models. Every request names its
model, so switching in Copilot switches per request. The config is read on
every request: a model added in the settings is offered without a restart.

With `gemini.prices` set, each request's cost is printed on the server's
console beside its token count.

## VS Code and JetBrains: Custom Endpoint

The same in both: in Copilot Chat's model picker choose **Manage Models** →
**Add Models** → **Custom Endpoint** (in older VS Code builds it was called
"OpenAI Compatible"). The form asks for a Model ID and a Deployment URL; add
one entry per model.

| Field | Value |
| --- | --- |
| Model ID | an id from the list, exactly — e.g. `gemini-3.8-flash` |
| Deployment URL | `http://localhost:11435/v1/chat/completions` |
| API key | the key `airelay serve` printed (`serve.token`) |
| Tool calling / vision | yes / yes |
| Max input / output tokens | `983040` / `65536` (VS Code adds the two and takes the sum as the context window) |

The server answers any path ending in `/chat/completions`, so it works whether
the client uses the URL as given or appends that itself, and Azure-style
`/openai/deployments/<model>/chat/completions` takes the model from the path.

**VS Code keeps these in `chatLanguageModels.json`**, which the Custom Endpoint
flow opens. One entry per model; `${input:airelayKey}` makes VS Code ask for the
key once and store it outside the file:

```json
[
  {
    "name": "AI Relay (Gemini)",
    "vendor": "customendpoint",
    "apiKey": "${input:airelayKey}",
    "apiType": "chat-completions",
    "models": [
      {
        "id": "gemini-3.8-flash",
        "name": "Gemini 3.8 Flash",
        "url": "http://localhost:11435/v1/chat/completions",
        "toolCalling": true,
        "vision": true,
        "maxInputTokens": 983040,
        "maxOutputTokens": 65536
      }
    ]
  }
]
```

A provider-level `"url": "http://localhost:11435/v1"` instead of the `models`
list lets VS Code discover the models from `/v1/models`. IntelliJ's form takes
the same values directly, with no file.

**VS Code shortcut.** With the AI Relay extension installed (VS Code 1.104 or
later), the models are already in Copilot's picker under **Manage Models** —
enable the **AI Relay** ones. The extension starts its own `airelay serve` on a
free port and stops it with VS Code; there is nothing to paste, and the list
follows your settings.

## Copilot CLI

Add a BYOK provider of type `openai` with base URL `http://localhost:11435/v1`
and the printed API key (see GitHub's *Adding LLM models to Copilot CLI*), then
pick one of the listed models.

## IntelliJ AI Chat: as a model

AI Assistant takes AI Relay as an OpenAI-compatible provider directly — no
custom agent and no OpenCode in between:

1. *Settings | Tools | AI Assistant | Providers & API keys* → **Third-party AI
   providers** → the OpenAI-compatible provider.
2. **URL** `http://localhost:11435/v1`, **API Key** the one `airelay serve`
   printed, and turn on **Tool calling**. **Test Connection**, then **Apply**.
3. The gateway's models appear in AI Chat's model selector, in their own
   section. To make one the default, pick it under **Model Assignment →
   Core features**.

## OpenCode

[OpenCode](https://opencode.ai) gets the gateway's models through
[opencode-apigee](https://github.com/Chelayel/opencode-apigee), a provider plugin
that signs in to the gateway itself — `airelay serve` does not need to run for it.

1. **Install OpenCode 1**, with one of OpenCode's own commands:

   ```bash
   curl -fsSL https://opencode.ai/install | bash      # macOS / Linux
   npm install -g opencode-ai                         # anywhere with Node
   brew install anomalyco/tap/opencode                # Homebrew
   ```

   Not `brew install opencode`: that formula installs OpenCode 2, whose plugin
   system opencode-apigee does not support yet.

2. **Add the plugin** to `~/.config/opencode/opencode.json`:

   ```json
   {
     "$schema": "https://opencode.ai/config.json",
     "plugin": ["opencode-apigee"]
   }
   ```

   OpenCode installs it from npm the next time it starts. It reads the gateway,
   project, token URL, client id and secret and the published models from AI
   Relay's config — what `airelay gemini setup` saved — so nothing else is
   needed. Without AI Relay, give them as plugin options; see the
   [plugin's README](https://github.com/Chelayel/opencode-apigee#setup).

3. **Try it in the terminal** before anything else uses it:

   ```bash
   opencode run --model apigee/gemini-3.8-flash "Say hello"
   ```

   with one of the models your gateway publishes in place of `gemini-3.8-flash`
   (`opencode models apigee` lists them). An answer means the gateway, token and
   model are right. In the TUI,
   `/models` → **Gemini via Apigee** picks it.

> **Not on OpenCode's Ecosystem page yet?** Its listing is waiting for review
> ([anomalyco/opencode#58431](https://github.com/anomalyco/opencode/pull/58431)).
> The listing is only a directory entry: the plugin is published on npm, and
> step 2 installs it the same way whether it is listed or not.

<details>
<summary>Or through <code>airelay serve</code>, without the plugin</summary>

Point OpenCode at `airelay serve` as an OpenAI-compatible provider instead
(one entry under `models` per gateway model; the key is the one `airelay serve`
printed):

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

</details>

## JetBrains Air (through OpenCode)

Air runs coding agents over ACP rather than taking a model endpoint, so the
Gemini models reach it through OpenCode, set up and tried in the terminal as
[above](#opencode): Air → OpenCode → your gateway.

1. The agent selector → **Add ACP Agent**, which opens Air's `acp.json`. If Air
   cannot find `opencode`, use the full path that `which opencode` prints — an
   app started from the Dock may not have Homebrew's folders on its PATH:

   ```json
   {
     "agent_servers": {
       "OpenCode (Gemini)": { "command": "opencode", "args": ["acp"] }
     }
   }
   ```

2. Pick **OpenCode (Gemini)** as the agent, and **Gemini via Apigee ›
   Gemini 3.8 Flash** as its model (`/models` in OpenCode switches it).

## Clients that only offer Ollama

Some clients take outside models only as an "Ollama" provider, which sends no
API key. Run the server without one and point the client at
`http://localhost:11435`:

```bash
airelay serve install --no-token
```

If the client has no field for the URL, it uses Ollama's default port: add
`--port 11434` (with no real Ollama running). The server answers
`/api/version`, `/api/tags`, `/api/show` and `/api/chat` the way Ollama does,
and `/v1/chat/completions`, which Ollama also serves.

## LiteLLM

Not needed — but if you want LiteLLM's logging or budgets in front, point it at
`airelay serve` as an OpenAI-compatible backend (`openai/<model>` with
`api_base: http://localhost:11435/v1` and the key).

## What is translated

Requests arrive as OpenAI Chat Completions (or Ollama's chat, rewritten to the
same shape) and go to Gemini's `streamGenerateContent`:

- `system` / `developer` messages → `systemInstruction`; user text and data-URL
  images → parts; assistant tool calls → `functionCall`; tool results →
  `functionResponse`, matched to their function by `tool_call_id`.
- Tool schemas are cut down to what Gemini's `parameters` accepts (`$ref`
  resolved, `allOf` merged, `oneOf` → `anyOf`, `["string","null"]` → nullable,
  `const` → enum, unknown keywords and formats dropped). If Gemini still
  refuses one, the request is sent again with the tools as full JSON Schema.
- `temperature`, `top_p`, `max_tokens`, `stop`, `seed`, `tool_choice` map to
  their Gemini names.
- **Thought signatures.** Gemini 3 signs each function call and rejects the next
  request unless the call comes back carrying the signature — which an OpenAI
  client never does. The server keeps each signature under the call id it
  issued (and the call's name and arguments, for Ollama clients) and puts it
  back. A signature it no longer has is replaced by Google's documented
  placeholder.
- An empty Gemini turn is asked again, as the agent does.
- Upstream errors keep a meaningful status: 429 stays 429, a bad request 400,
  anything else 502 with Gemini's message.
