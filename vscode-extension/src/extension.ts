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
    this.backend = this.context.workspaceState.get("backend") || cfg.get<string>("backend") || "claude";
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
        this.send(String(m.text || ""), !!m.attach);
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

  private send(text: string, attach: boolean) {
    if (this.busy || !text) return;
    const context = attach ? this.editorContext() : undefined;
    const prompt = !context
      ? text
      : context.selected !== undefined
        ? `Selected in \`${context.file}\` (lines ${context.start}\u2013${context.end}):\n\`\`\`\n${context.selected}\n\`\`\`\n\n${text}`
        : `Current file: \`${context.file}\`\n\n${text}`;
    this.page("user", text);
    this.busy = true;
    this.page("busy", true);
    this.ensureProcess()?.command({ type: "send", text: prompt });
  }

  private set(key: string, value: string) {
    if (key === "backend") {
      this.backend = value;
      this.context.workspaceState.update("backend", value);
      this.restart();
    } else if (key === "mode") {
      this.mode = value;
      this.context.workspaceState.update("mode", value);
      this.restart();
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
    const p = new RelayProcess(
      (e) => this.onEvent(e),
      (code, stderr) => this.onExit(code, stderr),
    );
    this.process = p;
    this.page("state", { status: `starting ${this.backend}…` });
    p.start(this.backend, folder, this.mode);
    return p;
  }

  private onEvent(e: Event) {
    const s = (k: string) => (typeof e[k] === "string" ? (e[k] as string) : "");
    switch (e.type) {
      case "ready":
        this.page("state", { status: s("describe") });
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
        this.page("permission", { id: e.id, name: s("name"), summary: s("summary") });
        break;
      case "turn_complete":
        this.busy = false;
        this.page("busy", false);
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
    this.page("state", { backend: this.backend, mode: this.mode });
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
      "--accent:var(--vscode-button-background);--abubble:var(--vscode-editorWidget-background);" +
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
