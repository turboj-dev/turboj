import * as vscode from 'vscode';
import { ChildProcessWithoutNullStreams, spawn } from 'node:child_process';
import { access } from 'node:fs/promises';
import * as path from 'node:path';

interface OffsetEdit { start: number; end: number; replacement: string }
interface Response {
  protocolVersion: number; id: string; sourceVersion?: number; success: boolean;
  formattedText?: string; edits?: OffsetEdit[]; cancelled?: boolean;
  error?: string; message?: string; diagnostics?: { message: string }[];
}
interface Pending { resolve: (value: Response) => void; reject: (error: Error) => void; timer: NodeJS.Timeout; cancel: vscode.Disposable }

/** One local JVM per extension host, started only when formatting is requested. */
class Backend implements vscode.Disposable {
  private child?: ChildProcessWithoutNullStreams;
  private readonly pending = new Map<string, Pending>();
  private serial = 0;
  private starts = 0;
  private readonly current = new Map<string, string>();
  private starting?: Promise<void>;
  private disposed = false;
  constructor(private readonly jar: string, private readonly log: vscode.OutputChannel) {}

  state() { return { pid: this.child?.pid, starts: this.starts, pending: this.pending.size }; }

  private async start() {
    if (this.disposed) throw new Error('javafmt was disposed.');
    if (this.child) return;
    if (this.starting) return this.starting;
    this.starting = (async () => {
      await access(this.jar);
      if (this.disposed) throw new Error('javafmt was disposed.');
      const executable = vscode.workspace.getConfiguration('javafmt').get<string>('javaExecutable', 'java');
      const child = spawn(executable, ['-Xmx512m', '-jar', this.jar, '--server'], {
        cwd: path.dirname(this.jar), windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'],
      });
      this.child = child;
      this.starts++;
      let buffer = '';
      let stderr = '';
      child.stdout.setEncoding('utf8');
      child.stderr.setEncoding('utf8');
      child.stdout.on('data', (chunk: string) => {
        buffer += chunk;
        if (buffer.length > 32 * 1024 * 1024) { this.stop(new Error('javafmt response exceeded its size limit.')); return; }
        let newline: number;
        while ((newline = buffer.indexOf('\n')) >= 0) {
          const line = buffer.slice(0, newline); buffer = buffer.slice(newline + 1);
          let response: Response;
          try { response = JSON.parse(line) as Response; }
          catch { this.stop(new Error('javafmt returned malformed JSON.')); return; }
          if (response.protocolVersion !== 1 || typeof response.id !== 'string') {
            this.stop(new Error('javafmt returned an unsupported protocol response.')); return;
          }
          const pending = this.pending.get(response.id);
          if (pending) { this.remove(response.id); pending.resolve(response); }
        }
      });
      child.stderr.on('data', (chunk: string) => { stderr = (stderr + chunk).slice(-4096); });
      child.on('error', () => {
        if (this.child === child) this.stop(new Error('Cannot start javafmt. Configure a Java 25 executable.'));
      });
      child.on('exit', (code) => {
        if (this.child !== child) return;
        if (stderr.trim()) this.log.appendLine(stderr.trim());
        this.stop(new Error(`javafmt exited (${code ?? 'signal'}). Java 25 is required.`));
      });
      child.stdin.on('error', () => { if (this.child === child) this.stop(new Error('javafmt input pipe closed.')); });
      await new Promise<void>((resolve, reject) => {
        child.once('spawn', resolve);
        child.once('error', () => reject(new Error('Cannot start javafmt. Configure a Java 25 executable.')));
      });
    })();
    try { await this.starting; } finally { this.starting = undefined; }
  }

  private remove(id: string) {
    const pending = this.pending.get(id);
    if (pending) { clearTimeout(pending.timer); pending.cancel.dispose(); this.pending.delete(id); }
  }

  private cancel(id: string) {
    const pending = this.pending.get(id);
    if (!pending) return;
    this.remove(id);
    this.child?.stdin.write(JSON.stringify({ protocolVersion: 1, op: 'cancel', id }) + '\n');
    pending.reject(new vscode.CancellationError());
  }

  cancelDocument(document: vscode.TextDocument) {
    const id = this.current.get(document.uri.toString());
    if (id) this.cancel(id);
  }

  async format(document: vscode.TextDocument, range: vscode.Range | undefined, token: vscode.CancellationToken): Promise<vscode.TextEdit[]> {
    if (token.isCancellationRequested) return [];
    const version = document.version;
    const source = document.getText();
    if (source.length > 4 * 1024 * 1024) throw new Error('javafmt source limit is 4 Mi UTF-16 units.');
    const identity = document.uri.toString();
    const previous = this.current.get(identity);
    if (previous) this.cancel(previous);
    await this.start();
    if (!this.child || this.disposed) throw new vscode.CancellationError();
    if (token.isCancellationRequested || document.version !== version) return [];
    const superseded = this.current.get(identity);
    if (superseded) this.cancel(superseded);
    const id = String(++this.serial);
    this.current.set(identity, id);
    const requestedRange = range ? { start: document.offsetAt(range.start), end: document.offsetAt(range.end) } : undefined;
    try {
      const response = await new Promise<Response>((resolve, reject) => {
        const timer = setTimeout(() => this.stop(new Error('javafmt timed out; its resident process was stopped.')), 30_000);
        const cancel = token.onCancellationRequested(() => this.cancel(id));
        this.pending.set(id, { resolve, reject, timer, cancel });
        this.child!.stdin.write(JSON.stringify({ protocolVersion: 1, id, source, sourceVersion: version, lineEndings: 'PRESERVE', range: requestedRange }) + '\n');
      });
      if (token.isCancellationRequested || document.isClosed || document.version !== version || this.current.get(identity) !== id) return [];
      if (!response.success) {
        if (response.cancelled) return [];
        const detail = response.diagnostics?.[0]?.message ?? response.message ?? response.error ?? 'Invalid Java source.';
        throw new Error(`javafmt: ${detail}`);
      }
      if (response.sourceVersion !== version || typeof response.formattedText !== 'string' || !Array.isArray(response.edits)) throw new Error('javafmt result does not match the source version.');
      let at = 0;
      const fragments: string[] = [];
      const edits = response.edits.map(edit => {
        if (!Number.isSafeInteger(edit.start) || !Number.isSafeInteger(edit.end) || edit.start < at || edit.end < edit.start || edit.end > source.length || typeof edit.replacement !== 'string') throw new Error('javafmt returned invalid or overlapping edits.');
        fragments.push(source.slice(at, edit.start), edit.replacement); at = edit.end;
        return vscode.TextEdit.replace(new vscode.Range(document.positionAt(edit.start), document.positionAt(edit.end)), edit.replacement);
      });
      fragments.push(source.slice(at));
      if (fragments.join('') !== response.formattedText) throw new Error('javafmt edit validation failed.');
      return edits;
    } finally { if (this.current.get(identity) === id) this.current.delete(identity); }
  }

  stop(error: Error) {
    const child = this.child; this.child = undefined;
    for (const [id, pending] of this.pending) { this.remove(id); pending.reject(error); }
    child?.kill();
  }
  dispose() { this.disposed = true; this.stop(new vscode.CancellationError()); }
}

export function activate(context: vscode.ExtensionContext) {
  const log = vscode.window.createOutputChannel('javafmt');
  const backend = new Backend(context.asAbsolutePath('server/javafmt.jar'), log);
  const format = async (document: vscode.TextDocument, range: vscode.Range | undefined, token: vscode.CancellationToken) => {
    try { return await backend.format(document, range, token); }
    catch (error) {
      if (!(error instanceof vscode.CancellationError)) log.appendLine(error instanceof Error ? error.message : 'javafmt failed.');
      return [];
    }
  };
  context.subscriptions.push(log, backend,
    vscode.languages.registerDocumentFormattingEditProvider({ language: 'java' }, {
      provideDocumentFormattingEdits(document, _options, token) { return format(document, undefined, token); },
    }),
    vscode.languages.registerDocumentRangeFormattingEditProvider({ language: 'java' }, {
      provideDocumentRangeFormattingEdits(document, range, _options, token) { return format(document, range, token); },
    }),
    vscode.workspace.onDidChangeConfiguration(event => {
      if (event.affectsConfiguration('javafmt.javaExecutable')) backend.stop(new vscode.CancellationError());
    }),
    vscode.workspace.onDidChangeTextDocument(event => backend.cancelDocument(event.document)),
    vscode.workspace.onDidCloseTextDocument(document => backend.cancelDocument(document)),
  );
  // Exporting these same functions allows integration tests inside the real extension host.
  return { state: () => backend.state(), format };
}

export function deactivate() { /* VS Code disposes registered resources. */ }
