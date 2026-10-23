import * as vscode from "vscode";
import * as cp from "child_process";
import * as fs from "fs";
import * as readline from "readline";

/**
 * AI Relay's Gemini connection as models in VS Code's chat model picker — the
 * one GitHub Copilot uses — through the Language Model Chat Provider API.
 *
 * Still a shell around the CLI: the provider starts `airelay serve --json`
 * (an OpenAI-compatible server on a free loopback port), and turns VS Code's
 * messages into chat-completion requests to it and the streamed answer back
 * into VS Code's parts. No model, credential or Gemini detail lives here —
 * the CLI holds the Apigee secret, translates to Gemini and keeps the thought
 * signatures. Copilot runs the agent and its own tools; this forwards calls.
 */

type Launch = { command: string; prefixArgs: string[] };
type Served = { url: string; token?: string; child: cp.ChildProcess };
type ModelEntry = { id: string; display_name?: string; context_length?: number; max_input_tokens?: number; max_output_tokens?: number; capabilities?: { tool_calling?: boolean; image_input?: boolean } };

export class RelayModelProvider implements vscode.LanguageModelChatProvider, vscode.Disposable {
  private served: Served | undefined;
  private starting: Promise<Served> | undefined;
  private readonly changed = new vscode.EventEmitter<void>();
  readonly onDidChangeLanguageModelChatInformation = this.changed.event;
  private readonly watcher: () => void;

  constructor(
    private readonly launch: () => { launch: Launch | undefined; env: NodeJS.ProcessEnv },
    configFile: string,
  ) {
    // A model added in the settings (apigee.agents, gemini.model) shows in the picker
    // without a reload: the server reads the config per request; the picker is told here.
    const listener = () => this.changed.fire();
    fs.watchFile(configFile, { interval: 2000 }, listener);
    this.watcher = () => fs.unwatchFile(configFile, listener);
  }

  dispose() {
    this.watcher();
    this.changed.dispose();
    this.served?.child.stdin?.end();
    this.served?.child.kill();
    this.served = undefined;
  }

  // ---- the server ------------------------------------------------------------

  private server(): Promise<Served> {
    if (this.served && this.served.child.exitCode === null) return Promise.resolve(this.served);
    if (this.starting) return this.starting;
    this.starting = new Promise<Served>((resolve, reject) => {
      const { launch, env } = this.launch();
      if (!launch) return reject(new Error("The airelay command was not found. Set airelay.path, or install AI Relay."));
      const batch = /\.(cmd|bat)$/i.test(launch.command);
      const q = (s: string) => (batch && /[\s"&|<>^()%!]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s);
      const child = cp.spawn(q(launch.command), [...launch.prefixArgs, "serve", "--json", "--port", "0"].map(q), { env, windowsHide: true, shell: batch });
      const stderr: string[] = [];
      readline.createInterface({ input: child.stderr! }).on("line", (l) => { stderr.push(l); if (stderr.length > 50) stderr.shift(); });
      const lines = readline.createInterface({ input: child.stdout! });
      lines.on("line", (line) => {
        let e: { type?: string; url?: string; token?: string; text?: string };
        try { e = JSON.parse(line); } catch { return; }
        if (e.type === "serving" && e.url) { this.served = { url: e.url, token: e.token, child }; resolve(this.served); }
        else if (e.type === "error") reject(new Error(e.text || "airelay serve failed to start."));
      });
      child.on("error", (err) => reject(new Error(`Could not run ${launch.command}: ${err.message}`)));
      child.on("exit", (code) => {
        if (this.served?.child === child) this.served = undefined;
        reject(new Error(stderr.join("\n").trim() || `airelay serve exited (${code}).`));
      });
    }).finally(() => { this.starting = undefined; });
    return this.starting;
  }

  private async call(pathname: string, init: RequestInit = {}): Promise<Response> {
    const s = await this.server();
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    if (s.token) headers.Authorization = `Bearer ${s.token}`;
    return fetch(s.url + pathname, { ...init, headers: { ...headers, ...(init.headers as Record<string, string> | undefined) } });
  }

  // ---- the picker ------------------------------------------------------------

  async provideLanguageModelChatInformation(options: vscode.PrepareLanguageModelChatModelOptions): Promise<vscode.LanguageModelChatInformation[]> {
    try {
      const res = await this.call("/v1/models");
      if (!res.ok) throw new Error(await errorText(res));
      const data = ((await res.json()) as { data?: ModelEntry[] }).data || [];
      return data.map((m) => ({
        id: m.id,
        name: m.display_name || m.id,
        family: m.id.toLowerCase().startsWith("gemini") ? "gemini" : m.id,
        version: m.id,
        detail: "AI Relay",
        tooltip: `${m.id} through AI Relay's Gemini connection`,
        // VS Code takes input + output as the window, so input is the window less the output budget.
        maxInputTokens: m.max_input_tokens || (m.context_length && m.max_output_tokens ? m.context_length - m.max_output_tokens : 120000),
        maxOutputTokens: m.max_output_tokens || 8192,
        capabilities: { toolCalling: m.capabilities?.tool_calling ?? true, imageInput: m.capabilities?.image_input ?? false },
      }));
    } catch (e) {
      // Silent is the picker filling itself in; only an explicit look says why it is empty.
      if (!options.silent) void vscode.window.showWarningMessage(`AI Relay models are unavailable: ${(e as Error).message}`, "Set Up Gemini").then((pick) => {
        if (pick) void vscode.commands.executeCommand("airelay.setup");
      });
      return [];
    }
  }

  async provideTokenCount(_model: vscode.LanguageModelChatInformation, text: string | vscode.LanguageModelChatRequestMessage): Promise<number> {
    // An estimate: Gemini counts on the server, and asking for every message would cost a round trip each.
    const s = typeof text === "string" ? text : text.content.map((p) => (p instanceof vscode.LanguageModelTextPart ? p.value : JSON.stringify(p) ?? "")).join("");
    return Math.ceil(s.length / 4);
  }

  // ---- a turn ----------------------------------------------------------------

  async provideLanguageModelChatResponse(
    model: vscode.LanguageModelChatInformation,
    messages: readonly vscode.LanguageModelChatRequestMessage[],
    options: vscode.ProvideLanguageModelChatResponseOptions,
    progress: vscode.Progress<vscode.LanguageModelResponsePart>,
    token: vscode.CancellationToken,
  ): Promise<void> {
    const body: Record<string, unknown> = { model: model.id, stream: true, messages: toOpenAi(messages) };
    if (options.tools?.length) {
      body.tools = options.tools.map((t) => ({
        type: "function",
        function: { name: t.name, description: t.description, parameters: t.inputSchema ?? { type: "object", properties: {} } },
      }));
      if (options.toolMode === vscode.LanguageModelChatToolMode.Required) body.tool_choice = "required";
    }
    const abort = new AbortController();
    const sub = token.onCancellationRequested(() => abort.abort());
    try {
      const res = await this.call("/v1/chat/completions", { method: "POST", body: JSON.stringify(body), signal: abort.signal });
      if (!res.ok || !res.body) throw new Error(await errorText(res));
      const calls = new Map<number, { id: string; name: string; args: string }>();
      for await (const data of sse(res.body)) {
        if (data === "[DONE]") break;
        let chunk: { error?: { message?: string }; choices?: { delta?: { content?: string; tool_calls?: { index?: number; id?: string; function?: { name?: string; arguments?: string } }[] } }[] };
        try { chunk = JSON.parse(data); } catch { continue; }
        if (chunk.error) throw new Error(chunk.error.message || "AI Relay reported an error.");
        const delta = chunk.choices?.[0]?.delta;
        if (delta?.content) progress.report(new vscode.LanguageModelTextPart(delta.content));
        for (const tc of delta?.tool_calls || []) {
          const i = tc.index ?? calls.size;
          const c = calls.get(i) || { id: "", name: "", args: "" };
          if (tc.id) c.id = tc.id;
          if (tc.function?.name) c.name = tc.function.name;
          if (tc.function?.arguments) c.args += tc.function.arguments;
          calls.set(i, c);
        }
      }
      for (const c of calls.values()) {
        let input: object = {};
        try { input = c.args ? JSON.parse(c.args) : {}; } catch { input = { input: c.args }; }
        progress.report(new vscode.LanguageModelToolCallPart(c.id, c.name, input));
      }
    } catch (e) {
      if (token.isCancellationRequested) return;
      throw e;
    } finally {
      sub.dispose();
    }
  }
}

/**
 * VS Code's messages as OpenAI's. A user message can carry tool results (the
 * answers to the previous turn's calls) beside its text; those go first, as
 * `tool` messages, which is where OpenAI wants them.
 */
function toOpenAi(messages: readonly vscode.LanguageModelChatRequestMessage[]): unknown[] {
  const out: unknown[] = [];
  for (const m of messages) {
    const text: string[] = [];
    const images: string[] = [];
    const calls: unknown[] = [];
    for (const p of m.content) {
      if (p instanceof vscode.LanguageModelTextPart) text.push(p.value);
      else if (p instanceof vscode.LanguageModelToolCallPart) {
        calls.push({ id: p.callId, type: "function", function: { name: p.name, arguments: JSON.stringify(p.input ?? {}) } });
      } else if (p instanceof vscode.LanguageModelToolResultPart) {
        out.push({ role: "tool", tool_call_id: p.callId, content: partsText(p.content) });
      } else if (p instanceof vscode.LanguageModelDataPart) {
        if (p.mimeType.startsWith("image/")) images.push(`data:${p.mimeType};base64,${Buffer.from(p.data).toString("base64")}`);
        else text.push(new TextDecoder().decode(p.data));
      }
    }
    const role = m.role === vscode.LanguageModelChatMessageRole.Assistant ? "assistant" : (m.role as number) === 3 ? "system" : "user";
    if (role === "assistant") {
      if (text.length || calls.length) out.push({ role, content: text.join("") || null, ...(calls.length ? { tool_calls: calls } : {}) });
    } else if (images.length) {
      out.push({ role, content: [...(text.length ? [{ type: "text", text: text.join("\n") }] : []), ...images.map((url) => ({ type: "image_url", image_url: { url } }))] });
    } else if (text.length) {
      out.push({ role, content: text.join("\n") });
    }
  }
  return out;
}

function partsText(parts: readonly unknown[]): string {
  return parts
    .map((p) => (p instanceof vscode.LanguageModelTextPart ? p.value : p instanceof vscode.LanguageModelDataPart ? new TextDecoder().decode(p.data) : typeof p === "string" ? p : JSON.stringify(p)))
    .join("\n");
}

/** The `data:` payloads of a server-sent-events stream. */
async function* sse(body: ReadableStream<Uint8Array>): AsyncGenerator<string> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let nl: number;
    while ((nl = buffer.indexOf("\n")) >= 0) {
      const line = buffer.slice(0, nl).trim();
      buffer = buffer.slice(nl + 1);
      if (line.startsWith("data:")) yield line.slice(5).trim();
    }
  }
}

async function errorText(res: Response): Promise<string> {
  const raw = await res.text().catch(() => "");
  try {
    return (JSON.parse(raw) as { error?: { message?: string } }).error?.message || `HTTP ${res.status}`;
  } catch {
    return raw.slice(0, 300) || `HTTP ${res.status}`;
  }
}
