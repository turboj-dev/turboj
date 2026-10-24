import * as vscode from 'vscode';
import * as assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import * as path from 'node:path';
import * as os from 'node:os';

interface TestApi {
  state(): { pid?: number; starts: number; pending: number };
  format(document: vscode.TextDocument, range: vscode.Range | undefined, token: vscode.CancellationToken): Promise<vscode.TextEdit[]>;
}

export async function run() {
  const report: string[] = [];
  const passed = (message: string) => { report.push(`PASS ${message}`); console.log(`PASS ${message}`); };
  const source = 'class C{int x=1;}';
  const expected = 'class C {\n    int x = 1;\n}\n';
  const extension = vscode.extensions.getExtension<TestApi>('javafmt-local.javafmt');
  assert.ok(extension, 'Extension is installed in the real development host');
  const api = await extension.activate();
  const root = process.env.JAVAFMT_TEST_ARTIFACTS ?? path.join(os.tmpdir(), 'javafmt-vscode-tests');
  await mkdir(root, { recursive: true });
  try {
    assert.equal(api.state().starts, 0, 'No process starts merely on activation');
    const document = await vscode.workspace.openTextDocument({ language: 'java', content: source });
    const editor = await vscode.window.showTextDocument(document);
    await vscode.workspace.getConfiguration().update('[java]', {
      'editor.defaultFormatter': 'javafmt-local.javafmt', 'editor.formatOnSave': true,
    }, vscode.ConfigurationTarget.Global);
    await vscode.commands.executeCommand('editor.action.formatDocument');
    assert.equal(document.getText(), expected);
    passed('Native Format Document updates an unsaved Java buffer');
    const pid = api.state().pid;
    assert.ok(pid);
    await vscode.commands.executeCommand('undo');
    assert.equal(document.getText(), source);
    passed('One native Undo restores the exact source');
    const edits = await vscode.commands.executeCommand<vscode.TextEdit[]>('vscode.executeFormatDocumentProvider', document.uri, { tabSize: 4, insertSpaces: true });
    assert.ok(edits && edits.length > 1);
    assert.ok(edits.every(edit => !edit.range.isEqual(new vscode.Range(document.positionAt(0), document.positionAt(document.getText().length)))));
    assert.equal(api.state().pid, pid);
    assert.equal(api.state().starts, 1);
    passed('Formatting returns multiple whitespace edits and reuses one JVM');

    const ranges = await vscode.workspace.openTextDocument({ language: 'java', content: 'class R{void a(){int x=1;}void b(){int y=2;}}' });
    await vscode.window.showTextDocument(ranges);
    const start = ranges.getText().indexOf('void b');
    const selected = new vscode.Range(ranges.positionAt(start), ranges.positionAt(ranges.getText().length - 1));
    const rangeEdits = await vscode.commands.executeCommand<vscode.TextEdit[]>('vscode.executeFormatRangeProvider', ranges.uri, selected, { tabSize: 4, insertSpaces: true });
    assert.ok(rangeEdits && rangeEdits.length > 0);
    assert.ok(rangeEdits.every(edit => ranges.offsetAt(edit.range.start) >= start));
    passed('Native range provider returns edits inside the selected method');

    const cancelled = new vscode.CancellationTokenSource();
    cancelled.cancel();
    assert.deepEqual(await api.format(document, undefined, cancelled.token), []);
    cancelled.dispose();
    passed('Cancellation returns no edits');

    const large = await vscode.workspace.openTextDocument({ language: 'java', content:
      'class Cancelled {' + Array.from({ length: 2000 }, (_, i) => `int m${i}(){return ${i};}`).join('') + '}' });
    const activeCancellation = new vscode.CancellationTokenSource();
    const activeRequest = api.format(large, undefined, activeCancellation.token);
    for (let i = 0; i < 100 && api.state().pending === 0; i++) await new Promise(resolve => setTimeout(resolve, 1));
    assert.equal(api.state().pending, 1, 'Cancellation is exercised after submitting a real backend request');
    activeCancellation.cancel();
    assert.deepEqual(await activeRequest, []);
    activeCancellation.dispose();
    passed('Cancellation suppresses an already submitted resident request');

    const staleDocument = await vscode.workspace.openTextDocument({ language: 'java', content: 'class Stale{int x=1;}' });
    const token = new vscode.CancellationTokenSource();
    const pending = api.format(staleDocument, undefined, token.token);
    const change = new vscode.WorkspaceEdit();
    change.insert(staleDocument.uri, new vscode.Position(0, 0), '// changed\n');
    await vscode.workspace.applyEdit(change);
    assert.deepEqual(await pending, []);
    token.dispose();
    assert.ok(staleDocument.getText().startsWith('// changed\n'));
    passed('Typing invalidates an in-flight formatting result');

    const invalid = await vscode.workspace.openTextDocument({ language: 'java', content: 'class {' });
    const invalidEdits = await vscode.commands.executeCommand<vscode.TextEdit[]>('vscode.executeFormatDocumentProvider', invalid.uri, { tabSize: 4, insertSpaces: true });
    assert.equal(invalidEdits?.length ?? 0, 0);
    assert.equal(invalid.getText(), 'class {');
    passed('Malformed Java is unchanged');

    const file = path.join(root, 'OnSave.java');
    await writeFile(file, 'class OnSave{int n=2;}');
    const saved = await vscode.workspace.openTextDocument(file);
    const savedEditor = await vscode.window.showTextDocument(saved);
    await savedEditor.edit(builder => builder.insert(new vscode.Position(0, 0), ' '));
    assert.ok(await saved.save());
    assert.equal(saved.getText(), 'class OnSave {\n    int n = 2;\n}\n');
    assert.equal(api.state().starts, 1);
    passed('Native format-on-save uses the same resident backend');

    process.kill(pid, 'SIGTERM');
    for (let i = 0; i < 100 && api.state().pid; i++) await new Promise(resolve => setTimeout(resolve, 20));
    await vscode.window.showTextDocument(document);
    await vscode.commands.executeCommand('editor.action.formatDocument');
    assert.equal(document.getText(), expected);
    assert.equal(api.state().starts, 2);
    passed('A terminated backend restarts on the next formatting request');

    const configuration = vscode.workspace.getConfiguration('javafmt');
    const executable = configuration.get<string>('javaExecutable', 'java');
    const recoveryToken = new vscode.CancellationTokenSource();
    await configuration.update('javaExecutable', path.join(root, 'missing-java-executable'), vscode.ConfigurationTarget.Global);
    assert.deepEqual(await api.format(document, undefined, recoveryToken.token), []);
    assert.equal(api.state().pending, 0, 'Spawn failure leaves no pending timer that could stop a later backend');
    await configuration.update('javaExecutable', executable, vscode.ConfigurationTarget.Global);
    const recoveredEdits = await api.format(ranges, undefined, recoveryToken.token);
    recoveryToken.dispose();
    assert.ok(recoveredEdits.length > 0);
    passed('An invalid Java executable fails safely and configuration recovery works');
    report.push(`VS Code ${vscode.version}; original resident PID ${pid}; new PID ${api.state().pid}`);
  } catch (error) {
    report.push(`FAIL ${error instanceof Error ? error.stack : String(error)}`);
    throw error;
  } finally {
    await writeFile(path.join(root, 'report.txt'), report.join('\n') + '\n');
  }
}
