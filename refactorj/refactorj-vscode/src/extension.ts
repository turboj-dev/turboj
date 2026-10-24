import * as vscode from 'vscode';
import { Backend } from './backend';

interface Source { file: string; text: string; version: number; open: boolean }
interface Prepared { schemaVersion: number; request: any; plan: { id: string; valid?: boolean; status: string; description: string; before: { root: string; sources: Source[] }; edits: { file: string; edits: { start: number; end: number; text: string }[] }[]; fileOperations: any[]; diagnostics: { severity: string; message: string }[] } }
const range = (value: any) => new vscode.Range(value.start.line, value.start.character, value.end.line, value.end.character);

export function activate(context: vscode.ExtensionContext) {
  const log = vscode.window.createOutputChannel('RefactorJ');
  const backends = new Map<string, Backend>(), previews = new Map<string, string>();
  const backend = (uri: vscode.Uri): Backend => {
    const folder = vscode.workspace.getWorkspaceFolder(uri); if (!folder) throw new Error('Open a trusted local workspace folder first.');
    if (uri.scheme !== 'file' || !vscode.workspace.isTrusted) throw new Error('RefactorJ requires a trusted local workspace.');
    let value = backends.get(folder.uri.toString());
    if (!value) { value = new Backend(folder, context.asAbsolutePath('server/lib'), log); backends.set(folder.uri.toString(), value); }
    return value;
  };
  const synchronize = async (engine: Backend) => {
    for (const document of vscode.workspace.textDocuments) if (document.languageId === 'java' && !document.isClosed && vscode.workspace.getWorkspaceFolder(document.uri)?.uri.toString() === engine.root.uri.toString()) await engine.synchronize(document);
  };
  const prepare = async (document: vscode.TextDocument, selection: vscode.Range, values: any, token?: vscode.CancellationToken): Promise<Prepared> => {
    const engine = backend(document.uri); await synchronize(engine);
    return engine.request('workspace/executeCommand', { command: 'refactorj.prepare', arguments: [{ ...values, location: { file: document.uri.fsPath, start: document.offsetAt(selection.start), end: document.offsetAt(selection.end) } }] }, token);
  };
  const apply = async (prepared: Prepared, token?: vscode.CancellationToken): Promise<boolean> => {
    const engine = backend(vscode.Uri.file(prepared.plan.before.root + '/placeholder.java')); await synchronize(engine);
    const wire = await engine.request('workspace/executeCommand', { command: 'refactorj.workspaceEdit', arguments: [prepared] }, token);
    if (token?.isCancellationRequested) return false;
    for (const expected of prepared.plan.before.sources) {
      const open = vscode.workspace.textDocuments.find(d => d.uri.fsPath === expected.file && !d.isClosed);
      if (open && open.getText() !== expected.text) throw new Error('Source changed after preview. Prepare a new refactoring.');
    }
    const edit = new vscode.WorkspaceEdit();
    const movedUris = new Map<string, string>();
    // VS Code saves dirty working copies during resource moves. Require an explicit save,
    // and perform resource changes before text edits. Native undo restores the editor
    // text/path; VS Code leaves the restored text dirty until the user saves it.
    for (const change of wire.documentChanges) if (!change.textDocument) {
      const original = change.oldUri ?? change.uri;
      const open = vscode.workspace.textDocuments.find(d => d.uri.toString() === original && !d.isClosed);
      if (open?.isDirty && (change.kind === 'rename' || change.kind === 'delete')) throw new Error('Save the affected file explicitly before moving or deleting it in VS Code; this host otherwise saves dirty buffers automatically.');
      if (change.kind === 'create') edit.createFile(vscode.Uri.parse(change.uri), { overwrite: false, ignoreIfExists: false });
      else if (change.kind === 'delete') edit.deleteFile(vscode.Uri.parse(change.uri), { recursive: false, ignoreIfNotExists: false });
      else if (change.kind === 'rename') { edit.renameFile(vscode.Uri.parse(change.oldUri), vscode.Uri.parse(change.newUri), { overwrite: false, ignoreIfExists: false }); movedUris.set(change.oldUri, change.newUri); }
      else throw new Error('Unsupported workspace edit.');
    }
    for (const change of wire.documentChanges) {
      if (change.textDocument) {
        const uri = vscode.Uri.parse(change.textDocument.uri), open = vscode.workspace.textDocuments.find(d => d.uri.toString() === uri.toString());
        if (change.textDocument.version !== null && change.textDocument.version !== undefined && open?.version !== change.textDocument.version) throw new Error('Document version changed after preview.');
        const destination = movedUris.has(uri.toString()) ? vscode.Uri.parse(movedUris.get(uri.toString())!) : uri;
        for (const replacement of change.edits) edit.replace(destination, range(replacement.range), replacement.newText);
      }
    }
    return vscode.workspace.applyEdit(edit, { isRefactoring: true });
  };
  const preview = async (prepared: Prepared) => {
    const errors = prepared.plan.diagnostics.filter(d => d.severity === 'ERROR' || d.severity === 'FATAL');
    if (prepared.plan.status !== 'VALID' || errors.length) throw new Error(errors.map(d => d.message).join('\n') || 'Refactoring has conflicts.');
    const files = new Map(prepared.plan.before.sources.map(s => [s.file, s.text]));
    const changed: { file: string; before: string; after: string }[] = [];
    for (const edits of prepared.plan.edits) {
      const before = files.get(edits.file); if (before === undefined) throw new Error('Preview source is missing.');
      let after = before; for (const edit of [...edits.edits].reverse()) after = after.slice(0, edit.start) + edit.text + after.slice(edit.end);
      files.set(edits.file, after); changed.push({ file: edits.file, before, after });
    }
    for (const op of prepared.plan.fileOperations) {
      if (op.kind === 'CREATE') changed.push({ file: op.path, before: '', after: op.content });
      if (op.kind === 'DELETE') changed.push({ file: op.path, before: files.get(op.path) ?? '', after: '' });
      if (op.kind === 'MOVE') changed.push({ file: `${op.path} → ${op.destination}`, before: files.get(op.path) ?? '', after: files.get(op.path) ?? '' });
    }
    if (!changed.length) { void vscode.window.showInformationMessage('RefactorJ: no changes required.'); return; }
    let selected = 0;
    for (;;) {
      const file = changed[selected]!; const before = vscode.Uri.parse(`refactorj-preview:/${prepared.plan.id}/${selected}/before.java`), after = vscode.Uri.parse(`refactorj-preview:/${prepared.plan.id}/${selected}/after.java`);
      previews.set(before.toString(), file.before); previews.set(after.toString(), file.after);
      await vscode.commands.executeCommand('vscode.diff', before, after, `RefactorJ ${selected + 1}/${changed.length}: ${file.file}`);
      const warnings = prepared.plan.diagnostics.filter(d => d.severity === 'WARNING').map(d => d.message).join('\n');
      const choice = await vscode.window.showInformationMessage(`${prepared.plan.description}\n${changed.length} affected changes. ${warnings}`, { modal: true }, 'Apply all changes', ...(changed.length > 1 ? ['Review next file'] : []));
      if (choice === 'Review next file') { selected = (selected + 1) % changed.length; continue; }
      if (choice === 'Apply all changes' && !await apply(prepared)) throw new Error('The editor did not apply the refactoring.');
      break;
    }
    previews.clear();
  };
  const refactor = async (requested?: any) => {
    const editor = vscode.window.activeTextEditor; if (!editor || editor.document.languageId !== 'java') return;
    try {
      const engine = backend(editor.document.uri); await synchronize(engine);
      let operation = requested?.operation as string | undefined;
      if (!operation) {
        const actions = await engine.request('textDocument/codeAction', { textDocument: { uri: editor.document.uri.toString() }, range: editor.selection, context: { diagnostics: [] } });
        const selected = await vscode.window.showQuickPick<{ label: string; operation: string }>(actions.map((a: any) => ({ label: a.title, operation: a.data.request.operation })), { title: 'RefactorJ — applicable semantic operations' });
        if (!selected) return; operation = selected.operation;
      }
      const catalog = await engine.request('workspace/executeCommand', { command: 'refactorj.catalog', arguments: [] });
      const fields = catalog.find((entry: any) => entry.operation === operation)?.fields ?? [];
      let values: any = { operation };
      if (fields.some((f: any) => f.name === 'name')) {
        const name = await vscode.window.showInputBox({ title: `RefactorJ — ${operation}`, prompt: 'New Java name', ignoreFocusOut: true }); if (name === undefined) return; values.name = name;
      }
      const complex = fields.filter((f: any) => f.name !== 'name');
      if (complex.length || operation === 'change-signature') {
        for (const field of complex) values[field.name] = field.type === 'boolean' ? operation === 'rename' && field.name === 'hierarchy' : field.values?.[0] ?? (field.type === 'array' ? [] : '');
        const text = await vscode.window.showInputBox({ title: `RefactorJ — ${operation} parameters`, prompt: 'Typed request JSON. Fields: ' + fields.map((f: any) => `${f.name}: ${f.elementType ?? f.values?.join('|') ?? f.type}`).join(', '), value: JSON.stringify(values), ignoreFocusOut: true,
          validateInput: text => { try { JSON.parse(text); return undefined; } catch { return 'Enter a JSON object.'; } } });
        if (text === undefined) return; values = JSON.parse(text); values.operation = operation;
      }
      await vscode.window.withProgress({ location: vscode.ProgressLocation.Notification, title: 'RefactorJ: preparing preview', cancellable: true }, async (_progress, token) => {
        const result = await prepare(editor.document, editor.selection, values, token); if (!token.isCancellationRequested) await preview(result);
      });
    } catch (error) { if (!(error instanceof vscode.CancellationError)) void vscode.window.showErrorMessage(error instanceof Error ? error.message : 'RefactorJ failed.'); }
  };
  context.subscriptions.push(log,
    vscode.workspace.registerTextDocumentContentProvider('refactorj-preview', { provideTextDocumentContent: uri => previews.get(uri.toString()) ?? '' }),
    vscode.commands.registerCommand('refactorj.refactor', () => refactor()),
    vscode.commands.registerCommand('refactorj.rename', () => refactor({ operation: 'rename' })),
    vscode.commands.registerCommand('refactorj.prepare', request => refactor(request)),
    vscode.commands.registerCommand('refactorj.configure', () => vscode.commands.executeCommand('workbench.action.openSettings', 'refactorj')),
    vscode.languages.registerCodeActionsProvider({ language: 'java', scheme: 'file' }, {
      async provideCodeActions(document, selection, _context, token) {
        try {
          const engine = backend(document.uri); await synchronize(engine);
          const actions = await engine.request('textDocument/codeAction', { textDocument: { uri: document.uri.toString() }, range: selection, context: { diagnostics: [] } }, token);
          return actions.map((action: any) => {
            const value = new vscode.CodeAction('RefactorJ: ' + action.title, vscode.CodeActionKind.Refactor);
            value.command = { title: action.title, command: 'refactorj.prepare', arguments: [action.data.request] }; return value;
          });
        } catch (error) { if (!(error instanceof vscode.CancellationError)) log.appendLine(String(error)); return []; }
      },
    }, { providedCodeActionKinds: [vscode.CodeActionKind.Refactor] }),
    vscode.languages.registerDefinitionProvider({ language: 'java', scheme: 'file' }, {
      async provideDefinition(document, position, token) {
        try { const engine = backend(document.uri); await synchronize(engine); const value = await engine.request('textDocument/definition', { textDocument: { uri: document.uri.toString() }, position }, token); return value ? new vscode.Location(vscode.Uri.parse(value.uri), range(value.range)) : undefined; }
        catch { return undefined; }
      },
    }),
    vscode.languages.registerReferenceProvider({ language: 'java', scheme: 'file' }, {
      async provideReferences(document, position, referenceContext, token) {
        try { const engine = backend(document.uri); await synchronize(engine); const values = await engine.request('textDocument/references', { textDocument: { uri: document.uri.toString() }, position, context: referenceContext }, token); return values.map((value: any) => new vscode.Location(vscode.Uri.parse(value.uri), range(value.range))); }
        catch { return []; }
      },
    }),
    vscode.workspace.onDidCloseTextDocument(document => { for (const engine of backends.values()) engine.close(document); }),
    vscode.workspace.onDidChangeConfiguration(event => { if (event.affectsConfiguration('refactorj')) { for (const engine of backends.values()) engine.dispose(); backends.clear(); } }),
    new vscode.Disposable(() => { for (const engine of backends.values()) engine.dispose(); }),
  );
  return { prepare, apply, state: (uri: vscode.Uri) => backend(uri).state() };
}
export function deactivate() {}
