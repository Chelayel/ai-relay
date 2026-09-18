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
    vscode.window.onDidChangeTextEditorSelection(() => this.pushState(), null, this.context.subscriptions);
  }

  // ---- page → host ---------------------------------------------------------

  private handle(m: { cmd: string; [k: string]: unknown }) {
    switch (m.cmd) {
      case "ready":
        this.pushState();
        this.ensureProcess();
        break;
      case "send":
        this.send(String(m.text || ""), !!m.includeSelection);
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
        vscode.commands.executeCommand("workbench.action.openSettings", "airelay");
        break;
      case "open":
        if (typeof m.url === "string") vscode.env.openExternal(vscode.Uri.parse(m.url));
        break;
    }
  }

  private send(text: string, includeSelection: boolean) {
    if (this.busy || !text) return;
    const selection = includeSelection ? this.selectionContext() : undefined;
    this.page("user", text);
    this.busy = true;
    this.page("busy", true);
    this.ensureProcess()?.command({ type: "send", text: selection ? `${selection}\n\n${text}` : text });
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

  /** The Gemini / Copilot wizards are terminal programs; open one for them. */
  setup() {
    vscode.window.showQuickPick(["gemini", "copilot"], { placeHolder: "Which agent to set up?" }).then((choice) => {
      if (!choice) return;
      const command = vscode.workspace.getConfiguration("airelay").get<string>("path") || "airelay";
      const terminal = vscode.window.createTerminal("AI Relay setup");
      terminal.show();
      terminal.sendText(`${command} ${choice} setup`);
    });
  }

  private selectionContext(): string | undefined {
    const editor = vscode.window.activeTextEditor;
    if (!editor || editor.selection.isEmpty) return undefined;
    const selected = editor.document.getText(editor.selection);
    if (!selected.trim()) return undefined;
    const file = vscode.workspace.asRelativePath(editor.document.uri);
    return `Selected in \`${file}\` (from line ${editor.selection.start.line + 1}):\n\`\`\`\n${selected}\n\`\`\``;
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
        text += `\n\nRun \`airelay ${this.backend} setup\` in a terminal (command: AI Relay: Set Up an Agent), then start a new conversation.`;
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
    const editor = vscode.window.activeTextEditor;
    this.page("state", {
      backend: this.backend,
      mode: this.mode,
      selectionAvailable: !!editor && !editor.selection.isEmpty,
    });
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
