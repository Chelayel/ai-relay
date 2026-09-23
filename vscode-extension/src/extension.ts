import * as vscode from "vscode";
import * as cp from "child_process";
import * as fs from "fs";
import * as path from "path";
import * as readline from "readline";

/**
 * AI Relay for VS Code: the shared chat page in a webview, and an
 * `airelay <backend> --json` process behind it. The extension never talks to
 * a model — it launches the CLI and relays lines (see docs/protocol.md in the
 * repository), which is why it behaves exactly like the terminal and the
 * IntelliJ plugin. It needs the `airelay` command installed; the
 * `airelay.path` setting says where.
 */
export function activate(context: vscode.ExtensionContext) {
  const provider = new ChatViewProvider(context);
  context.subscriptions.push(
    vscode.window.registerWebviewViewProvider("airelay.chat", provider, {
      webviewOptions: { retainContextWhenHidden: true },
    }),
    vscode.commands.registerCommand("airelay.new", () => provider.newConversation()),
    vscode.commands.registerCommand("airelay.focus", () => vscode.commands.executeCommand("airelay.chat.focus")),
    vscode.commands.registerCommand("airelay.setup", () => provider.setup()),
    provider,
  );
}

export function deactivate() {}

// ---- the CLI's config file ----------------------------------------------------
// ~/.airelay/config.properties: dotted keys, one per line, shared with the CLI
// and the IntelliJ plugin. Written owner-only, since it holds keys.

function configFilePath(): string {
  if (process.env.AIRELAY_CONFIG) return process.env.AIRELAY_CONFIG;
  return path.join(process.env.HOME || process.env.USERPROFILE || ".", ".airelay", "config.properties");
}

function readConfigFile(): Record<string, string> {
  const out: Record<string, string> = {};
  let text = "";
  try {
    text = fs.readFileSync(configFilePath(), "utf8");
  } catch {
    return out;
  }
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith("#") || line.startsWith("!")) continue;
    const m = line.match(/^([^=:\s]+)\s*[=:]\s*(.*)$/);
    if (m) out[m[1]] = unescapeProperty(m[2]);
  }
  return out;
}

function writeConfigFile(updates: Record<string, string>) {
  const merged = { ...readConfigFile() };
  for (const [k, v] of Object.entries(updates)) {
    if (v.trim() === "") delete merged[k];
    else merged[k] = v.trim();
  }
  const file = configFilePath();
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const body =
    "# AI Relay configuration — shared by the CLI and the IDE plugins\n" +
    Object.keys(merged)
      .sort()
      .map((k) => `${k}=${escapeProperty(merged[k])}`)
      .join("\n") +
    "\n";
  fs.writeFileSync(file, body, { mode: 0o600 });
  try {
    fs.chmodSync(file, 0o600);
  } catch {
    /* Windows */
  }
}

// java.util.Properties escaping, enough for keys, URLs and secrets.
function escapeProperty(v: string): string {
  return v.replace(/\\/g, "\\\\").replace(/\n/g, "\\n").replace(/\r/g, "\\r").replace(/\t/g, "\\t").replace(/^ /, "\\ ");
}
function unescapeProperty(v: string): string {
  return v.replace(/\\(.)/g, (_, c) => (c === "n" ? "\n" : c === "r" ? "\r" : c === "t" ? "\t" : c));
}

type Event = { type: string; [k: string]: unknown };

/** One airelay process. */
class RelayProcess {
  private child: cp.ChildProcess | undefined;
  private stderr: string[] = [];

  constructor(
    private readonly onEvent: (e: Event) => void,
    private readonly onExit: (code: number | null, stderr: string) => void,
  ) {}

  start(backend: string, cwd: string, mode: string) {
    const cfg = vscode.workspace.getConfiguration("airelay");
    const command = (cfg.get<string>("path") || "airelay").trim();
    const extra = (cfg.get<string>("extraArgs") || "").split(" ").filter((a) => a.length > 0);
    const args = [backend, "--json", "--dir", cwd, "--permission-mode", mode, ...extra];
    // A GUI-launched VS Code can have a bare PATH; the agent shells out to git, gradle, claude…
    const home = process.env.HOME || process.env.USERPROFILE || "";
    const extraPath = [path.join(home, ".local", "bin"), "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin"];
    const env = { ...process.env, PATH: extraPath.join(path.delimiter) + path.delimiter + (process.env.PATH || "") };
    const child = cp.spawn(command, args, { cwd, env, windowsHide: true });
    this.child = child;
    child.on("error", (err) => this.onExit(null, `Could not run \`${command}\`: ${err.message}`));
    readline.createInterface({ input: child.stdout! }).on("line", (line) => {
      try {
        this.onEvent(JSON.parse(line));
      } catch {
        /* not an event line */
      }
    });
    readline.createInterface({ input: child.stderr! }).on("line", (line) => this.stderr.push(line));
    child.on("exit", (code) => this.onExit(code, this.stderr.join("\n").trim()));
  }

  get alive(): boolean {
    return !!this.child && this.child.exitCode === null && !this.child.killed;
  }

  command(obj: object) {
    this.child?.stdin?.write(JSON.stringify(obj) + "\n");
  }

  stop() {
    const child = this.child;
    if (!child) return;
    this.command({ type: "exit" });
    setTimeout(() => {
      if (child.exitCode === null) child.kill("SIGKILL");
    }, 3000);
  }
}

class ChatViewProvider implements vscode.WebviewViewProvider, vscode.Disposable {
  private view: vscode.WebviewView | undefined;
  private process: RelayProcess | undefined;
  private busy = false;
  private backend: string;
  private mode: string;

  constructor(private readonly context: vscode.ExtensionContext) {
    const cfg = vscode.workspace.getConfiguration("airelay");
    this.backend = this.context.workspaceState.get("backend") || cfg.get<string>("backend") || "gemini";
    this.mode = this.context.workspaceState.get("mode") || cfg.get<string>("permissionMode") || "acceptEdits";
  }

  resolveWebviewView(view: vscode.WebviewView) {
    this.view = view;
    view.webview.options = { enableScripts: true, localResourceRoots: [this.context.extensionUri] };
    view.webview.html = this.html(view.webview);
    view.webview.onDidReceiveMessage((m) => this.handle(m));
    view.onDidDispose(() => {
      this.process?.stop();
      this.process = undefined;
      this.view = undefined;
    });
    // The context chip is live: it follows the selection and the active editor.
    vscode.window.onDidChangeTextEditorSelection(() => this.pushContext(), null, this.context.subscriptions);
    vscode.window.onDidChangeActiveTextEditor(() => this.pushContext(), null, this.context.subscriptions);
  }

  // ---- page → host ---------------------------------------------------------

  private handle(m: { cmd: string; [k: string]: unknown }) {
    switch (m.cmd) {
      case "ready":
        this.pushState();
        this.pushContext();
        this.ensureProcess();
        break;
      case "send":
        this.send(
          String(m.text || ""),
          !!m.attach,
          Array.isArray(m.files) ? (m.files as string[]) : [],
          Array.isArray(m.skills) ? (m.skills as string[]) : [],
          Array.isArray(m.inline) ? (m.inline as { name: string; text: string }[]) : [],
          Array.isArray(m.images) ? (m.images as { name: string; mimeType: string; data: string }[]) : [],
        );
        break;
      case "attachUris": {
        const uris = Array.isArray(m.uris) ? (m.uris as string[]) : [];
        const paths: string[] = [];
        for (const u of uris) {
          try { paths.push(vscode.workspace.asRelativePath(vscode.Uri.parse(u))); } catch { /* not a uri */ }
        }
        if (paths.length) this.page("attached", paths);
        break;
      }
      case "attach":
        this.attachFiles();
        break;
      case "mcp":
        this.openMcpConfig();
        break;
      case "revert":
        this.process?.command(typeof m.id === "string" ? { type: "revert", id: m.id } : { type: "revert" });
        break;
      case "sessions":
        this.ensureProcess()?.command(typeof m.query === "string" && m.query ? { type: "sessions", query: m.query } : { type: "sessions" });
        break;
      case "resume":
        this.ensureProcess()?.command({ type: "resume", id: String(m.id || "") });
        break;
      case "model":
        this.process?.command({ type: "model", name: String(m.name || "") });
        break;
      case "agent":
        this.ensureProcess()?.command({ type: "agent", name: String(m.name || "") });
        break;
      case "addDir":
        this.process?.command({ type: "add_dir", path: String(m.path || "") });
        break;
      case "files":
        this.matchFiles(String(m.query || "")).then((list) => this.page("files", list));
        break;
      case "open":
        this.openInEditor(String(m.path || ""), typeof m.line === "number" ? m.line : undefined);
        break;
      case "draft":
        this.context.workspaceState.update("draft", String(m.text || ""));
        break;
      case "applyFence":
        this.applyFence(String(m.path || ""), String(m.text || ""));
        break;
      case "cancel":
        this.process?.command({ type: "cancel" });
        break;
      case "permission":
        this.process?.command({ type: "permission", id: m.id, decision: m.decision });
        break;
      case "set":
        this.set(String(m.key), String(m.value));
        break;
      case "new":
        this.newConversation();
        break;
      case "settings":
        this.setup();
        break;
      case "open":
        if (typeof m.url === "string") vscode.env.openExternal(vscode.Uri.parse(m.url));
        break;
    }
  }

  private send(
    text: string,
    attach: boolean,
    files: string[],
    skills: string[],
    inline: { name: string; text: string }[] = [],
    images: { name: string; mimeType: string; data: string }[] = [],
  ) {
    if (this.busy || !text) return;
    const context = attach ? this.editorContext() : undefined;
    const parts: string[] = [];
    if (context) {
      parts.push(
        context.selected !== undefined
          ? `Selected in \`${context.file}\` (lines ${context.start}\u2013${context.end}):\n\`\`\`\n${context.selected}\n\`\`\``
          : `Current file: \`${context.file}\``,
      );
    }
    if (files.length) parts.push("Attached from the workspace (read them as needed):\n" + files.map((f) => `- \`${f}\``).join("\n"));
    for (const f of inline) parts.push(`Dropped file \`${f.name}\`:\n\`\`\`\n${f.text}\n\`\`\``);
    parts.push(text);
    const prompt = parts.join("\n\n");
    this.page("user", text);
    this.busy = true;
    this.page("busy", true);
    const cmd: Record<string, unknown> = { type: "send", text: prompt };
    if (skills.length) cmd.skills = skills;
    if (images.length) cmd.images = images.filter((i) => i && i.data);
    this.ensureProcess()?.command(cmd);
  }

  private set(key: string, value: string) {
    if (key === "backend") {
      this.backend = value;
      this.context.workspaceState.update("backend", value);
      this.restart();
    } else if (key === "mode") {
      this.mode = value;
      this.context.workspaceState.update("mode", value);
      // A live change: the conversation is kept, the agent just runs tools differently from here on.
      if (this.process?.alive) this.process.command({ type: "mode", name: value });
      else this.restart();
    }
  }

  newConversation() {
    this.page("clear");
    this.restart();
  }

  /**
   * Guided setup: the same questions the CLI wizard asks, as VS Code input
   * boxes, written to the CLI's own config file — so the command line, this
   * extension and the IntelliJ plugin share one configuration.
   */
  async setup() {
    const agent = await vscode.window.showQuickPick(
      [
        { label: "Gemini", detail: "Gemini API key, Vertex AI, or Vertex behind Apigee", id: "gemini" },
        { label: "Copilot", detail: "Your signed-in Microsoft Copilot web session", id: "copilot" },
        { label: "Web search", detail: "Let the agent search the web (Brave, Tavily or Google)", id: "web" },
        { label: "Claude", detail: "Nothing to set up — uses the claude CLI's own login", id: "claude" },
      ],
      { placeHolder: "What to set up?" },
    );
    if (!agent) return;
    const current = readConfigFile();
    const ask = async (key: string, prompt: string, opts: { password?: boolean; placeHolder?: string } = {}) => {
      const value = await vscode.window.showInputBox({
        prompt,
        value: opts.password ? "" : current[key] || "",
        placeHolder: opts.password && current[key] ? "(unchanged — a value is already saved)" : opts.placeHolder,
        password: opts.password,
        ignoreFocusOut: true,
      });
      if (value === undefined) throw new Error("cancelled");
      return value.trim() === "" && opts.password ? current[key] || "" : value.trim();
    };
    const updates: Record<string, string> = {};
    try {
      if (agent.id === "claude") {
        vscode.window.showInformationMessage("Claude needs no setup: install the claude CLI and sign in with `claude` once.");
        return;
      }
      if (agent.id === "gemini") {
        const mode = await vscode.window.showQuickPick(
          [
            { label: "Gemini API", detail: "an API key from Google AI Studio", id: "gemini-api" },
            { label: "Vertex AI", detail: "a Google Cloud project; token from gcloud", id: "vertex" },
            { label: "Vertex via Apigee", detail: "a corporate gateway with OAuth client credentials", id: "apigee" },
          ],
          { placeHolder: "How do you reach Gemini?" },
        );
        if (!mode) return;
        updates["gemini.mode"] = mode.id;
        if (mode.id === "gemini-api") {
          updates["gemini.api.key"] = await ask("gemini.api.key", "Gemini API key", { password: true });
        } else {
          updates["vertex.project"] = await ask("vertex.project", "Google Cloud project ID");
          updates["vertex.location"] = (await ask("vertex.location", "Location", { placeHolder: "us-central1" })) || "us-central1";
          if (mode.id === "apigee") {
            updates["vertex.endpoint"] = await ask("vertex.endpoint", "Apigee gateway host", { placeHolder: "my-gw.example.com" });
            updates["apigee.token.url"] = await ask("apigee.token.url", "OAuth token URL");
            updates["apigee.client.id"] = await ask("apigee.client.id", "Client ID");
            updates["apigee.client.secret"] = await ask("apigee.client.secret", "Client secret", { password: true });
            updates["apigee.agents"] = await ask("apigee.agents", "Model ids the gateway publishes, comma-separated");
          }
        }
        updates["gemini.model"] = await ask("gemini.model", "Model", { placeHolder: "gemini-3.7-flash" });
      } else if (agent.id === "copilot") {
        const mode = await vscode.window.showQuickPick(
          [
            { label: "Browser", detail: "Drive a real Chrome/Edge tab you sign in to — the mode for Microsoft 365 Copilot", id: "browser" },
            { label: "Replay", detail: "Replay one captured request (needs `airelay copilot setup --replay` in a terminal)", id: "replay" },
          ],
          { placeHolder: "How should Copilot be driven?" },
        );
        if (!mode) return;
        if (mode.id === "replay") {
          const terminal = vscode.window.createTerminal("AI Relay setup");
          terminal.show();
          terminal.sendText(`${vscode.workspace.getConfiguration("airelay").get<string>("path") || "airelay"} copilot setup --replay`);
          return;
        }
        updates["copilot.mode"] = "browser";
        updates["copilot.url"] = (await ask("copilot.url", "Copilot page", { placeHolder: "https://m365.cloud.microsoft/chat" })) || "https://m365.cloud.microsoft/chat";
      } else {
        const provider = await vscode.window.showQuickPick(["brave", "tavily", "google"], { placeHolder: "Search provider" });
        if (!provider) return;
        updates["search.provider"] = provider;
        updates["search.api.key"] = await ask("search.api.key", `${provider} API key`, { password: true });
        if (provider === "google") updates["search.cx"] = await ask("search.cx", "Programmable Search engine id (cx)");
      }
    } catch (e) {
      if ((e as Error).message === "cancelled") return;
      throw e;
    }
    writeConfigFile(updates);
    vscode.window.showInformationMessage(`Saved to ${configFilePath()}. Start a new conversation to use it.`);
    this.newConversation();
  }

  /** `@query` in the composer: workspace files whose path contains every word of the query, best first. */
  private async matchFiles(query: string): Promise<string[]> {
    const words = query.toLowerCase().split(/[\s/]+/).filter((w) => w.length > 0);
    const uris = await vscode.workspace.findFiles("**/*", "{**/node_modules/**,**/.git/**,**/build/**,**/out/**,**/target/**,**/dist/**}", 4000);
    const scored: [number, string][] = [];
    for (const u of uris) {
      const rel = vscode.workspace.asRelativePath(u);
      const lower = rel.toLowerCase();
      if (!words.every((w) => lower.includes(w))) continue;
      const name = path.basename(lower);
      const last = words[words.length - 1] || "";
      const score = (last && name.startsWith(last) ? 0 : last && name.includes(last) ? 1 : 2) * 1000 + rel.length;
      scored.push([score, rel]);
    }
    return scored.sort((a, b) => a[0] - b[0]).map((s) => s[1]).slice(0, 40);
  }

  private resolveInWorkspace(p: string): string | undefined {
    const clean = p.split(":")[0].replace(/^~\//, (process.env.HOME || "") + "/");
    if (path.isAbsolute(clean)) return fs.existsSync(clean) ? clean : undefined;
    for (const f of vscode.workspace.workspaceFolders || []) {
      const full = path.join(f.uri.fsPath, clean);
      if (fs.existsSync(full)) return full;
    }
    return undefined;
  }

  /** A path from the transcript (a diff header, a tool row): open it, at `line` when known. */
  private async openInEditor(p: string, line?: number) {
    const full = this.resolveInWorkspace(p);
    if (!full) { this.page("error", `Not found: ${p}`); return; }
    const doc = await vscode.workspace.openTextDocument(vscode.Uri.file(full));
    const editor = await vscode.window.showTextDocument(doc, { preview: true });
    if (line && line > 0) {
      const pos = new vscode.Position(Math.min(line - 1, doc.lineCount - 1), 0);
      editor.selection = new vscode.Selection(pos, pos);
      editor.revealRange(new vscode.Range(pos, pos), vscode.TextEditorRevealType.InCenter);
    }
  }

  /** "Apply to file" on a code fence: write the block to that path, creating it if needed, and open it. */
  private async applyFence(p: string, text: string) {
    const folder = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
    if (!folder) return;
    const full = this.resolveInWorkspace(p) || path.join(folder, p);
    try {
      fs.mkdirSync(path.dirname(full), { recursive: true });
      fs.writeFileSync(full, text.endsWith("\n") ? text : text + "\n");
    } catch (e) {
      this.page("error", `Could not write ${p}: ${(e as Error).message}`);
      return;
    }
    await this.openInEditor(full);
    this.page("system", `Wrote ${vscode.workspace.asRelativePath(full)}`);
  }

  /** The "+" menu's file picker: paths go to the page as chips, and into the next message. */
  private async attachFiles() {
    const picked = await vscode.window.showOpenDialog({
      canSelectMany: true,
      canSelectFiles: true,
      canSelectFolders: true,
      openLabel: "Attach",
      title: "Attach to the next message",
    });
    if (picked?.length) this.page("attached", picked.map((u) => vscode.workspace.asRelativePath(u)));
  }

  /**
   * Open the MCP config the CLI will read, creating an empty one when there is
   * none. Same search order as the CLI: `mcp.config`, `~/.airelay/mcp.json`,
   * `.mcp.json` in the workspace. Servers apply from the next conversation.
   */
  private async openMcpConfig() {
    const home = process.env.HOME || process.env.USERPROFILE || ".";
    const folder = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
    const candidates: string[] = [];
    const configured = readConfigFile()["mcp.config"];
    if (configured) candidates.push(configured);
    candidates.push(path.join(home, ".airelay", "mcp.json"));
    if (folder) candidates.push(path.join(folder, ".mcp.json"));
    // The CLI merges every file it finds, project last, so the project one is what to edit.
    let file = [...candidates].reverse().find((f) => fs.existsSync(f));
    if (!file) {
      file = candidates[0];
      fs.mkdirSync(path.dirname(file), { recursive: true });
      fs.writeFileSync(file, '{\n  "mcpServers": {\n  }\n}\n');
    }
    const doc = await vscode.workspace.openTextDocument(vscode.Uri.file(file));
    await vscode.window.showTextDocument(doc, { preview: false });
    this.page("system", `MCP servers: ${file} — the same "mcpServers" shape Claude Desktop uses. Saved servers apply to the next conversation (New).`);
  }

  /** What the active editor offers: its file, and the selected lines if any. */
  private editorContext(): { file: string; start?: number; end?: number; selected?: string } | undefined {
    const editor = vscode.window.activeTextEditor;
    if (!editor || editor.document.uri.scheme !== "file") return undefined;
    const file = vscode.workspace.asRelativePath(editor.document.uri);
    const selected = editor.selection.isEmpty ? "" : editor.document.getText(editor.selection);
    if (!selected.trim()) return { file };
    return { file, start: editor.selection.start.line + 1, end: editor.selection.end.line + 1, selected };
  }

  private pushContext() {
    const c = this.editorContext();
    this.page("context", c ? { file: c.file, start: c.start, end: c.end } : null);
  }

  // ---- process -------------------------------------------------------------

  private ensureProcess(): RelayProcess | undefined {
    if (this.process?.alive) return this.process;
    const folder = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
    if (!folder) {
      this.page("error", "Open a folder first: the agent works over a project.");
      return undefined;
    }
    // A replaced process exits after its successor started; its exit must
    // not report the successor as "not running".
    const p = new RelayProcess(
      (e) => this.onEvent(e),
      (code, stderr) => { if (this.process === p) this.onExit(code, stderr); },
    );
    this.process = p;
    this.pushState();
    this.page("state", { status: `starting ${this.backend}…` });
    p.start(this.backend, folder, this.mode);
    return p;
  }

  private onEvent(e: Event) {
    const s = (k: string) => (typeof e[k] === "string" ? (e[k] as string) : "");
    switch (e.type) {
      case "ready":
        this.page("state", {
          status: s("describe"),
          model: s("model"),
          models: Array.isArray(e.models) ? e.models : [],
          agent: s("agent"),
          agents: Array.isArray(e.agents) ? e.agents : [],
          workspace: Array.isArray(e.workspace) ? e.workspace : [],
          mcp: Array.isArray(e.mcp) ? e.mcp : [],
          skills: Array.isArray(e.skills) ? e.skills : [],
        });
        break;
      case "text":
        this.page("assistant", s("text"));
        break;
      case "thinking":
        this.page("thinking", s("text"));
        break;
      case "tool_use":
        this.page("tool", s("name"), s("summary"));
        break;
      case "file_changed":
        this.page("fileChanged", s("path"), s("diff"), s("revertId"));
        break;
      case "usage":
        this.page("usage", { contextTokens: e.contextTokens, costUsd: e.costUsd });
        break;
      case "sessions":
        this.page("sessions", Array.isArray(e.list) ? e.list : []);
        break;
      case "replay_start":
        this.page("replayStart", s("id"), s("title"));
        break;
      case "replay_end":
        this.page("replayEnd", s("id"), !!e.resumed);
        break;
      case "user":
        this.page("user", s("text"));
        break;
      case "tool_result":
        this.page("toolResult", s("text"), !!e.isError);
        break;
      case "info":
      case "stopped":
        this.page("system", s("text"));
        break;
      case "error":
        this.page("error", s("text"));
        break;
      case "permission":
        this.page("permission", { id: e.id, name: s("name"), summary: s("summary"), detail: s("detail") });
        break;
      case "turn_complete":
        this.busy = false;
        this.page("busy", false);
        this.page("turnDone", { elapsedMs: e.elapsedMs, files: Array.isArray(e.files) ? e.files : [], commands: e.commands });
        break;
    }
  }

  private onExit(code: number | null, stderr: string) {
    this.busy = false;
    this.page("busy", false);
    if (code !== 0 || stderr) {
      let text = stderr || `airelay exited with status ${code}.`;
      if (/ENOENT|Could not run/.test(text)) {
        text += "\n\nInstall the airelay command (https://github.com/Chelayel/ai-relay#install) or set `airelay.path` to it.";
      } else if (this.backend === "gemini" || this.backend === "copilot") {
        text += `\n\nSet it up with the command palette: AI Relay: Set Up an Agent (or the ⚙ button), then start a new conversation.`;
      }
      this.page("error", text);
    }
    this.page("state", { status: "not running" });
  }

  private restart() {
    this.process?.stop();
    this.process = undefined;
    this.busy = false;
    this.page("busy", false);
    this.ensureProcess();
  }

  private pushState() {
    this.page("state", { backend: this.backend, mode: this.mode, draft: this.context.workspaceState.get<string>("draft") || "" });
  }

  // ---- host → page ---------------------------------------------------------

  private page(fn: string, ...args: unknown[]) {
    this.view?.webview.postMessage({ fn, args });
  }

  private html(webview: vscode.Webview): string {
    const file = path.join(this.context.extensionPath, "media", "chat.html");
    const nonce = Array.from({ length: 24 }, () => "abcdefghijklmnopqrstuvwxyz0123456789"[Math.floor(Math.random() * 36)]).join("");
    // VS Code exposes its theme as --vscode-* variables; map them onto the page's.
    const theme =
      "--bg:var(--vscode-sideBar-background);--fg:var(--vscode-foreground);" +
      "--dim:var(--vscode-descriptionForeground);--border:var(--vscode-panel-border,var(--vscode-widget-border,#444));" +
      "--accent:var(--vscode-button-background);--abubble:var(--vscode-editorWidget-background);--tone:var(--airelay-tone,dark);" +
      "--ububble:var(--vscode-list-activeSelectionBackground);--code:var(--vscode-textCodeBlock-background);" +
      "--font:var(--vscode-font-family);--codefont:var(--vscode-editor-font-family);--fs:var(--vscode-font-size);";
    return fs
      .readFileSync(file, "utf8")
      .replace("{{theme}}", theme)
      .replace(/\{\{cspSource\}\}/g, webview.cspSource)
      .replace(/\{\{nonce\}\}/g, nonce);
  }

  dispose() {
    this.process?.stop();
  }
}
