import * as vscode from 'vscode';
import { spawn, ChildProcessWithoutNullStreams } from 'node:child_process';
import * as path from 'node:path';

interface Pending { resolve(value: any): void; reject(error: Error): void; timer: NodeJS.Timeout; cancellation: vscode.Disposable }
/** One resident Java 25 LSP process per workspace. All transformations stay in the Java engine. */
export class Backend implements vscode.Disposable {
  private process?: ChildProcessWithoutNullStreams;
  private starting?: Promise<void>;
  private serial = 0;
  private readonly pending = new Map<number, Pending>();
  private readonly opened = new Map<string, number>();
  private disposed = false;
  constructor(readonly root: vscode.WorkspaceFolder, private readonly library: string, private readonly log: vscode.OutputChannel) {}
  state() { return { pid: this.process?.pid, pending: this.pending.size }; }
  async start() {
    if (this.disposed) throw new Error('RefactorJ is closed.');
    if (this.starting) return this.starting;
    if (this.process) return;
    this.starting = (async () => {
      const settings = vscode.workspace.getConfiguration('refactorj', this.root.uri);
      const sources = settings.get<string[]>('sourceRoots', []), classpath = settings.get<string[]>('classpath', []);
      const args = ['-Xmx768m', '-cp', path.join(this.library, '*'), 'dev.turboj.refactorj.lsp.Main', '--root', this.root.uri.fsPath];
      if (sources.length) args.push('--source-root', sources.join(path.delimiter));
      if (classpath.length) args.push('--classpath', classpath.join(path.delimiter));
      const child = spawn(settings.get<string>('javaExecutable', 'java'), args, { cwd: this.root.uri.fsPath, windowsHide: true });
      this.process = child; let buffer = Buffer.alloc(0), expected = -1, stderr = '';
      child.stdout.on('data', (chunk: Buffer) => {
        buffer = Buffer.concat([buffer, chunk]);
        if (buffer.length > 64 * 1024 * 1024) { this.stop(new Error('RefactorJ response limit exceeded.')); return; }
        for (;;) {
          if (expected < 0) {
            const boundary = buffer.indexOf('\r\n\r\n'); if (boundary < 0) break;
            const match = /^Content-Length: (\d+)$/mi.exec(buffer.subarray(0, boundary).toString('ascii'));
            if (!match || Number(match[1]) > 64 * 1024 * 1024) { this.stop(new Error('Invalid LSP frame.')); return; }
            expected = Number(match[1]); buffer = buffer.subarray(boundary + 4);
          }
          if (buffer.length < expected) break;
          try {
            const response = JSON.parse(buffer.subarray(0, expected).toString('utf8')); buffer = buffer.subarray(expected); expected = -1;
            if (response.jsonrpc !== '2.0') throw new Error('Invalid JSON-RPC response.');
            const pending = this.pending.get(response.id);
            if (pending) { this.remove(response.id); response.error ? pending.reject(new Error(response.error.message)) : pending.resolve(response.result); }
          } catch (error) { this.stop(error instanceof Error ? error : new Error('Invalid response.')); return; }
        }
      });
      child.stderr.on('data', (chunk: Buffer) => { stderr = (stderr + chunk.toString()).slice(-4096); });
      child.on('error', error => { if (this.process === child) this.stop(new Error(`Cannot start Java 25: ${error.message}`)); });
      child.on('exit', () => { if (this.process === child) { if (stderr.trim()) this.log.appendLine(stderr); this.stop(new Error('RefactorJ stopped. Java 25 is required.')); } });
      child.stdin.on('error', error => { if (this.process === child) this.stop(error); });
      await new Promise<void>((resolve, reject) => { child.once('spawn', resolve); child.once('error', reject); });
      await this.raw('initialize', { rootUri: this.root.uri.toString(), capabilities: { workspace: { workspaceEdit: { documentChanges: true, resourceOperations: ['create', 'rename', 'delete'] } }, general: { positionEncodings: ['utf-16'] } } });
      this.notify('initialized', {});
    })();
    try { await this.starting; } finally { this.starting = undefined; }
  }
  private send(value: unknown) {
    if (!this.process) throw new Error('RefactorJ is not running.');
    const bytes = Buffer.from(JSON.stringify(value), 'utf8'); this.process.stdin.write(`Content-Length: ${bytes.length}\r\n\r\n`); this.process.stdin.write(bytes);
  }
  private remove(id: number) { const value = this.pending.get(id); if (value) { clearTimeout(value.timer); value.cancellation.dispose(); this.pending.delete(id); } }
  notify(method: string, params: unknown) { this.send({ jsonrpc: '2.0', method, params }); }
  private raw(method: string, params: unknown, token?: vscode.CancellationToken): Promise<any> {
    if (token?.isCancellationRequested) return Promise.reject(new vscode.CancellationError());
    const id = ++this.serial;
    return new Promise((resolve, reject) => {
      const cancel = () => { const pending = this.pending.get(id); if (!pending) return; this.remove(id); try { this.notify('$/cancelRequest', { id }); } catch {} pending.reject(new vscode.CancellationError()); };
      const cancellation = token?.onCancellationRequested(cancel) ?? new vscode.Disposable(() => {});
      const timer = setTimeout(() => { cancel(); }, 60_000);
      this.pending.set(id, { resolve, reject, timer, cancellation });
      try { this.send({ jsonrpc: '2.0', id, method, params }); } catch (error) { this.remove(id); reject(error); }
    });
  }
  async request(method: string, params: unknown, token?: vscode.CancellationToken): Promise<any> { await this.start(); return this.raw(method, params, token); }
  async synchronize(document: vscode.TextDocument) {
    await this.start(); const uri = document.uri.toString(), version = this.opened.get(uri);
    if (version === document.version) return;
    if (version === undefined) this.notify('textDocument/didOpen', { textDocument: { uri, languageId: 'java', version: document.version, text: document.getText() } });
    else this.notify('textDocument/didChange', { textDocument: { uri, version: document.version }, contentChanges: [{ text: document.getText() }] });
    this.opened.set(uri, document.version);
  }
  close(document: vscode.TextDocument) { const uri = document.uri.toString(); if (this.opened.delete(uri) && this.process) this.notify('textDocument/didClose', { textDocument: { uri } }); }
  private stop(error: Error) { const child = this.process; this.process = undefined; this.opened.clear(); for (const [id, pending] of this.pending) { this.remove(id); pending.reject(error); } child?.kill(); }
  dispose() { this.disposed = true; this.stop(new vscode.CancellationError()); }
}
