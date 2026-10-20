# The Copilot web backend (`airelay copilot`)

[← README](../README.md)

This is the backend that drives your signed-in **Microsoft 365 Copilot** web session as a coding agent. It is separate from using GitHub Copilot with your Gemini models, which is `airelay serve` ([serve.md](serve.md)).

## Setup

Copilot has no API key and no separate login: you are already signed in to the
website through your organisation's SSO, and this backend **reuses that session**.
There are two ways it can do that.

## Browser mode — for M365 Copilot

```bash
airelay copilot setup --browser
```

**Use this for `m365.cloud.microsoft`.** That Copilot streams its chat over a
WebSocket, so there is no request to replay — the reply never travels over one.
Browser mode instead keeps a Copilot tab open and uses it the way you would:
it types your prompt into the message box, presses Enter, and reads the answer
off the frames the page's own WebSocket receives.

Setup stores nothing but a URL — no session token, because the browser keeps it,
and nothing to re-capture when it expires.

- A browser window opens on your **first** turn so you can sign in. After that it
  runs **headless** — no window, nothing in the way of your terminal.
- If the sign-in later lapses, the hidden browser finds no message box and
  reopens visibly by itself (`Copilot needs signing in again — opening a
  window.`) rather than timing out at you.
- **Pick the model in that window** — it applies to every turn. `-m` and
  `/model` don't apply here; the picker in the page is the picker. Note this is
  the one thing headless costs you: to change model, set `copilot.headless=false`
  for a run, pick it, then go back to hidden.
- Leave the window open while you work.

Copilot is told the working directory and given a listing of the project, then
works like a coding agent: read the relevant files, change them, run the build or
tests, keep going until the task is done. A turn looks like this:

```
⚙ readFile src/Greet.kt
  ↳ read 1 line (33 chars)
⚙ editFile src/Greet.kt
  Edited src/Greet.kt (1 occurrence)
  - "hi "
  + "hello "
⚙ runCommand grep -c hello src/Greet.kt
  exit 0
Changed the greeting in src/Greet.kt and verified it.
```

A turn does not end just because Copilot stopped talking. Three ways a chat
model hands back half-finished work are each pushed back on once:

| It does this | The loop does this |
| --- | --- |
| Writes code out instead of applying it | `No tool call in that reply — asking Copilot to apply it, not describe it.` |
| Changes files but never checks them | `Changed 1 file(s) without checking — asking Copilot to verify.` |
| Asks "would you like me to…?" | `That reply asked whether to continue — telling Copilot to just do it.` |

At most three pushes per turn, so a model that genuinely is finished can finish.
Repeating the same tool call with the same arguments is refused after twice, so
a turn can't spin. Each turn closes with what it actually did —
`changed 1 file(s): src/Greet.kt; ran 1 command(s)`.

Edits go through `editFile`, which replaces an exact snippet rather than
rewriting the file — necessary here, because a chat composer caps a message at a
few kilobytes and a whole source file does not fit. A snippet that matches
nothing, or matches several places, is refused rather than guessed at. Long new
files can be built with repeated `writeFile` calls using `append`, and large
files read a piece at a time with `readFile` `offset`/`limit`.

Everything stays inside the workspace roots (`-C` sets the directory, `--add-dir`
adds more) — a path that escapes them is refused.

The answer is read off the page's own WebSocket where that is readable, and
otherwise from the newest message block on the page. Either way the reply is
stripped of any echo of your own prompt before it is used.

If a turn fails, the error names the text boxes it actually found on the page,
and what the one it typed into ended up holding — enough to pick a
`copilot.selector.input` without guessing. `copilot.debug=true` adds a line per
turn saying whether the answer was read from the socket or scraped off the page,
and how many frames were seen — the first thing to check if answers look wrong.

| Key | Meaning |
| --- | --- |
| `copilot.url` | Page to drive (default `https://m365.cloud.microsoft/chat`). |
| `copilot.selector.input` | CSS for the message box, if the automatic guess picks the wrong one. |
| `copilot.attach.port` | Attach to a browser you started with `--remote-debugging-port`. |
| `copilot.headless` | `auto` (default: window until you've signed in once, hidden after), `true`, or `false`. |
| *(shared)* | One browser per machine: a second `airelay copilot` (another project in the IDE, another terminal) attaches to the browser the first one opened instead of launching its own. The browser is closed when no airelay has used it for `idle.minutes`. |
| `copilot.quiet.ms` | Silence that marks the end of an answer (default 2500). |
| `copilot.max.message.chars` | Message cap — a composer has a length limit an API wouldn't (default 7000). |

Copilot is a chat assistant underneath, so it will sometimes *write out* a change
rather than applying it. The tool contract is therefore restated briefly each
turn, and a reply that contains code but calls no tool gets one nudge — you'll
see `No tool call in that reply — asking Copilot to apply it, not describe it.`
Project memory (`CLAUDE.md`) is capped to a third of the message budget so it
can't crowd the contract out.

## Replay mode — for a Copilot that chats over HTTP

```bash
airelay copilot setup
```

That opens a browser on Copilot and watches it. You sign in as usual, send the
one short message it shows you, and it reads the resulting request straight off
the wire — URL, headers, cookies and body, at any size. Nothing is copied or
pasted by hand.

The browser runs against a profile in `~/.airelay/browser`, so the SSO login
sticks: re-capturing later usually needs no sign-in at all.

From that one request AI Relay derives the endpoint, the session headers, where
the prompt goes in the body, and which field the model picker writes to — so
nothing about the endpoint is hard-coded and a different tenant, or a change to
the site, is just another capture.

| Option | Meaning |
| --- | --- |
| `--url URL` | Page to open (default `https://m365.cloud.microsoft/chat`). |
| `--attach PORT` | Use a browser you started yourself with `--remote-debugging-port=PORT`, instead of launching one. |
| `--timeout SECONDS` | How long to wait for you to sign in (default 300). |
| `--file PATH` | Skip the browser and read a saved `Copy as cURL` from a file. |

`AIRELAY_BROWSER` points at a Chrome/Chromium/Edge binary if it isn't found
automatically; `AIRELAY_BROWSER_ARGS` adds flags to the launched browser.

> **Don't paste a cURL at a prompt.** A terminal truncates any single pasted
> line at about 4 KB and a Copilot request is tens of KB, so the command arrives
> silently cut in half. That is why `--file` takes a path rather than a paste,
> and why a truncated capture is now rejected outright instead of being saved
> and failing later. The browser capture avoids the problem entirely.

| Thing | How it's handled |
| --- | --- |
| **Session** | Whatever `Authorization`/`Cookie` the browser sent. It expires with the browser session; `airelay copilot login` re-captures it and keeps your model settings. |
| **Model choice** | If the captured body carried a model id, `-m NAME` and `/model NAME` write to that same field. `airelay copilot models` lists what you saved. |
| **System prompt** | None. Copilot is not told who it is; it gets your message, a sketch of the project and the tool contract. `copilot.system.prompt` adds a preface if you want one. |
| **History** | By default only the new message is sent and the Copilot conversation remembers the rest, exactly as the site works. Set `copilot.history=local` to re-send a transcript instead. |
| **Tools** | The endpoint is a chat surface with no function-calling, so the tool contract is taught in the preamble and requested as ```` ```tool ```` JSON blocks, which are hidden from the transcript and shown as `⚙ readFile` lines. |

## "Connected, but no assistant text could be found"

Two things cause this. Either the wrong request was captured — a page-state
endpoint answers with the app's store, and your message comes back as a
conversation title rather than a reply — in which case re-run `airelay copilot
setup` and pick a different request from the list. Or the right request was
captured and only the *shape* of the reply is unrecognised, because your tenant
streams its text under a field name the built-in list doesn't know. To tell
which, run:

```bash
airelay copilot diagnose
```

It replays one request, writes the whole response to
`~/.airelay/last-response.txt`, then ranks every string field in it — prose
scores above ids, URLs and enum labels — and offers to save the winner to
`copilot.text.keys`:

```
Fields that could hold the answer  (best first)
  1) spokenText  at spokenText  ·  3 chunk(s), 48 chars
     "I am Microsoft 365 Copilot, your work assistant."
  2) author      at author      ·  3 chunk(s), 7 chars
     "copilot"
```

`copilot.debug` is **on by default** while this backend is still being shaped
against real tenants. Every turn reports how it was read:

```
browser: answer read from the socket; sockets 1, frames out 1, frames in 2, parsed 2; at https://…
```

When a turn comes back wrong, "read off the socket" versus "read off the page"
is the first thing worth knowing — and nobody thinks to switch a flag on before
the run that went wrong. Set `copilot.debug=false` to silence it.

> **Note.** This talks to an undocumented endpoint using your own session. It can
> break whenever the site changes, and it may not be permitted by your terms of
> use — check before relying on it. The capture is a live credential: it is
> stored in `~/.airelay/config.properties`, written owner-only (`0600`), and
> `airelay copilot reset` removes it.
