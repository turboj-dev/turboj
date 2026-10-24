package dev.turboj.refactorj.lsp;

import com.google.gson.*;
import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.Model.*;
import dev.turboj.refactorj.cli.*;
import dev.turboj.refactorj.transactions.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/** LSP 3.17 UTF-16 adapter. Returns versioned WorkspaceEdits; the editor owns application/undo. */
public final class LspHandler implements RpcServer.Handler, AutoCloseable {
    private final Path allowedRoot;
    private final List<Path> sourceRoots, classpath;
    private WorkspaceSession session;
    private InMemoryWorkspace buffers;
    private DiskWorkspace disk;
    private final Map<Path, Integer> versions = new HashMap<> ();
    private boolean shutdown;
    private boolean cleanExit;

    @Override public boolean stopAfter(String method) { return method.equals("exit"); }
    public boolean cleanExit() { return cleanExit; }

    public LspHandler(Path root, List<Path> sourceRoots, List<Path> classpath) {
        allowedRoot = root;
        this.sourceRoots = List.copyOf(sourceRoots);
        this.classpath = List.copyOf(classpath);
    }

    @Override
    public JsonElement handle(String method, JsonObject params,
            Cancellation cancellation) throws Exception {
        if (method.equals("initialize"))
            return initialize(params);
        if (method.equals("exit")) {
            cleanExit = shutdown;
            return JsonNull.INSTANCE;
        }
        if (session == null)
            throw new RpcServer.RpcError(-32002, "Server not initialized");
        if (method.equals("shutdown")) {
            shutdown = true;
            return JsonNull.INSTANCE;
        }
        if (shutdown)
            throw new RpcServer.RpcError(-32600, "Server has shut down");
        if (!Set.of("initialized", "$/setTrace", "workspace/didChangeConfiguration",
                "textDocument/didOpen", "textDocument/didChange", "textDocument/didClose")
                .contains(method))
            synchronizeClosedFiles();
        return switch (method) {
            case "initialized", "$/setTrace", "workspace/didChangeConfiguration",
                    "textDocument/didSave", "workspace/didChangeWatchedFiles" -> JsonNull.INSTANCE;
            case "textDocument/didOpen" -> {
                open(params);
                yield JsonNull.INSTANCE;
            }
            case "textDocument/didChange" -> {
                change(params);
                yield JsonNull.INSTANCE;
            }
            case "textDocument/didClose" -> {
                closeDocument(params);
                yield JsonNull.INSTANCE;
            }
            case "textDocument/prepareRename" -> {
                var source = source(params);
                Location location = location(params, source);
                var engine = session.engine(cancellation);
                var available = engine.available(location, cancellation).stream().anyMatch(d -> d
                        .operation() == Operation.RENAME && d.applicable());
                Symbol symbol = engine.workspace().resolve(location, cancellation);

                if (!available || symbol == null)
                    yield JsonNull.INSTANCE;
                int offset = location.start(), start = offset, end = offset;

                while (start > 0 && Character.isJavaIdentifierPart(source.text().codePointBefore(start)))
                    start -= Character.charCount(source.text().codePointBefore(start));
                while (end < source.text().length() && Character.isJavaIdentifierPart(source.text().codePointAt(end)))
                    end += Character.charCount(source.text().codePointAt(end));
                yield Json.object("range", Json.range(source.text(), start, end), "placeholder",
                        source.text().substring(start, end));
            }
            case "textDocument/rename" -> {
                var source = source(params);
                Location location = location(params, source);
                var prepared = session.prepare(Json.request(new Requests.Rename(location, Json
                        .string(params, "newName"))), cancellation);
                yield workspaceEdit(prepared.plan());
            }
            case "textDocument/definition" -> {
                var source = source(params);
                Symbol symbol = session.engine(cancellation).workspace().resolve(location(params,
                        source), cancellation);
                yield symbol == null || symbol.declaration()
                        == null ? JsonNull.INSTANCE : location(symbol.declaration());
            }
            case "textDocument/references" -> {
                var source = source(params);
                var workspace = session.engine(cancellation).workspace();
                Symbol symbol = workspace.resolve(location(params, source), cancellation);
                JsonArray result = new JsonArray();

                if (symbol != null)
                    for (Location reference : workspace.references(symbol.id(), cancellation)) {
                        boolean declaration = symbol.declaration() != null && reference
                                .equals(symbol.declaration());

                        if (!declaration || params.has("context") && Json.bool(params
                                .getAsJsonObject("context"), "includeDeclaration", false))
                            result.add(location(reference));
                    }
                yield result;
            }
            case "workspace/symbol", "textDocument/documentSymbol" -> {
                Path only = method.startsWith("textDocument") ? source(params).file() : null;
                JsonArray result = new JsonArray();

                for (Symbol symbol : session.engine(cancellation).workspace().symbols(Json
                        .string(params, "query", ""), cancellation)) {
                    if (symbol.declaration() == null || only != null && !only.equals(symbol
                            .declaration().file()))
                        continue;
                    result.add(Json.object("name", symbol.name(), "kind", symbolKind(symbol.kind()),
                            "location", location(symbol.declaration()), "containerName", symbol
                            .signature()));
                }
                yield result;
            }
            case "textDocument/codeAction" -> codeActions(params, cancellation);
            case "codeAction/resolve" -> resolveAction(params, cancellation);
            case "workspace/executeCommand" -> execute(params, cancellation);
            default -> throw new RpcServer.RpcError(-32601, "Method not supported: " + method);
        };
    }

    private JsonObject initialize(JsonObject params) throws Exception {
        if (session != null)
            throw new RpcServer.RpcError(-32600, "Already initialized");
        if (params.has("rootUri") && !params.get("rootUri").isJsonNull() && !Path.of(URI.create(Json
                .string(params, "rootUri"))).toRealPath().equals(allowedRoot))
            throw new IllegalArgumentException("Client root differs from the allowed root");
        disk = DiskWorkspace.open(allowedRoot, sourceRoots, classpath);
        buffers = new InMemoryWorkspace(disk.snapshot());
        session = new WorkspaceSession(buffers);
        return Json.object("capabilities", Json.object("positionEncoding", "utf-16",
                "textDocumentSync", Json.object("openClose", true, "change", 2, "save", true),
                "renameProvider", Json.object("prepareProvider", true), "definitionProvider", true,
                "referencesProvider", true, "documentSymbolProvider", true,
                "workspaceSymbolProvider", true, "codeActionProvider", Json
                .object("resolveProvider", true, "codeActionKinds", List.of("refactor", "quickfix",
                "source.organizeImports")), "executeCommandProvider", Json.object("commands", List
                .of("refactorj.catalog", "refactorj.prepare", "refactorj.validate",
                "refactorj.workspaceEdit"))), "serverInfo", Json.object("name", "RefactorJ",
                "version", "0.1.0"));
    }

    private void synchronizeClosedFiles() {
        var onDisk = disk.snapshot();
        var current = session.snapshot();
        var files = new HashSet<Path> ();

        for (var source : onDisk.sources()) {
            files.add(source.file());
            if (versions.containsKey(source.file()))
                continue;
            var previous = current.sources().stream().filter(s -> s.file().equals(source.file()))
                    .findFirst();

            if (previous.isEmpty() || !previous.get().text().equals(source.text()))
                buffers.update(source.file(), source.text(), false);
        }
        for (var source : current.sources())
            if (!versions.containsKey(source.file()) && !files.contains(source.file()))
                buffers.remove(source.file());
    }

    private void open(JsonObject params) {
        JsonObject document = params.getAsJsonObject("textDocument");
        Path file = file(Json.string(document, "uri"));

        if (versions.containsKey(file))
            throw new IllegalArgumentException("Document already open");
        buffers.update(file, Json.string(document, "text"), true);
        versions.put(file, Json.integer(document, "version"));
    }

    private void change(JsonObject params) {
        JsonObject document = params.getAsJsonObject("textDocument");
        Path file = file(Json.string(document, "uri"));
        int version = Json.integer(document, "version");
        Integer previous = versions.get(file);

        if (previous == null || version <= previous)
            throw new IllegalArgumentException("Unopened or stale document change");
        String text = session.snapshot().source(file).text();

        for (JsonElement element : params.getAsJsonArray("contentChanges")) {
            JsonObject change = element.getAsJsonObject();
            String replacement = Json.string(change, "text");

            if (!change.has("range"))
                text = replacement;
            else {
                JsonObject range = change.getAsJsonObject("range");
                int start = offset(text, range.getAsJsonObject("start")), end = offset(text, range
                        .getAsJsonObject("end"));

                if (end < start)
                    throw new IllegalArgumentException("Reversed change range");
                text = text.substring(0, start) + replacement + text.substring(end);
            }
        }
        buffers.update(file, text, true);
        versions.put(file, version);
    }

    private void closeDocument(JsonObject params) throws Exception {
        Path file = file(Json.string(params.getAsJsonObject("textDocument"), "uri"));
        versions.remove(file);
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            buffers.update(file, dev.turboj.refactorj.cli.Main.readLimited(file), false);
        else
            buffers.remove(file);
    }

    private JsonArray codeActions(JsonObject params, Cancellation cancellation) {
        SourceSnapshot source = source(params);
        var range = params.getAsJsonObject("range");
        Location selected = new Location(source.file(), offset(source.text(), range
                .getAsJsonObject("start")), offset(source.text(), range.getAsJsonObject("end")));
        JsonArray actions = new JsonArray();

        for (var descriptor : session.engine(cancellation).available(selected, cancellation)) {
            if (!descriptor.applicable())
                continue;
            String kind = descriptor.operation()
                    == Operation.ORGANIZE_IMPORTS ? "source.organizeImports" : descriptor
                    .operation().ordinal() >= Operation.ADD_IMPORT
                    .ordinal() ? "quickfix" : "refactor";
            JsonObject context = params.getAsJsonObject("context");

            if (context != null && context.has("only") && context.getAsJsonArray("only").asList()
                    .stream().noneMatch(x -> kind.equals(x.getAsString()) || kind.startsWith(x
                    .getAsString() + ".")))
                continue;
            JsonObject request = Json.object("operation", descriptor.operation().id(), "location",
                    selected);
            actions.add(Json.object("title", descriptor.title(), "kind", kind, "data", Json
                    .object("request", request, "documentVersion", source.version()), "command",
                    Json.object("title", descriptor.title(), "command", "refactorj.prepare",
                    "arguments", List.of(request))));
        }
        return actions;
    }

    private JsonObject resolveAction(JsonObject action, Cancellation cancellation) {
        JsonObject copy = action.deepCopy(), data = copy.getAsJsonObject("data"), request = data
                .getAsJsonObject("request");
        Location location = Json.request(request, allowedRoot).location();

        if (data.get("documentVersion").getAsLong() != session.snapshot().source(location.file())
                .version())
            throw new RpcServer.RpcError(-32801, "Source changed after code action");
        try {
            var prepared = session.prepare(request, cancellation);

            if (prepared.plan().valid()) {
                copy.add("edit", workspaceEdit(prepared.plan()));
                copy.remove("command");
            } else
                copy.add("disabled", Json.object("reason", prepared.plan().diagnostics().stream()
                        .map(Diagnostic::message).findFirst()
                        .orElse("Additional refactoring parameters required")));
        } catch (IllegalArgumentException invalid) {
            copy.add("disabled", Json.object("reason",
                    "Provide operation parameters through refactorj.prepare: " + invalid
                    .getMessage()));
        }
        return copy;
    }

    private JsonElement execute(JsonObject params, Cancellation cancellation) {
        String command = Json.string(params, "command");
        JsonArray arguments = params.getAsJsonArray("arguments");

        if (command.equals("refactorj.catalog"))
            return Json.catalog();
        if (arguments == null || arguments.size() != 1)
            throw new IllegalArgumentException("Exactly one structured argument required");
        if (command.equals("refactorj.prepare"))
            return Json.GSON.toJsonTree(session.prepare(arguments.get(0).getAsJsonObject(),
                    cancellation));
        var prepared = Json.GSON.fromJson(arguments.get(0), WorkspaceSession.Prepared.class);

        if (command.equals("refactorj.validate"))
            return Json.GSON.toJsonTree(session.validatePrepared(prepared, cancellation));
        if (command.equals("refactorj.workspaceEdit"))
            return workspaceEdit(session.validatePrepared(prepared, cancellation).plan());
        throw new RpcServer.RpcError(-32602, "Unknown command");
    }

    public JsonObject workspaceEdit(Plan plan) {
        synchronizeClosedFiles();
        if (!plan.valid())
            throw new RpcServer.RpcError(-32803, "Refactoring has conflicts", Json.GSON
                    .toJsonTree(plan.diagnostics()));
        ChangeSet.prepare(plan);
        WorkspaceSnapshot now = session.snapshot();

        if (now.version() != plan.before().version() || !now.sources().equals(plan.before()
                .sources()))
            throw new RpcServer.RpcError(-32801, "Source changed after preparation");
        JsonArray changes = new JsonArray();

        for (FileEdits edits : plan.edits()) {
            SourceSnapshot source = plan.before().source(edits.file());
            JsonArray values = new JsonArray();

            for (TextEdit edit : edits.edits())
                values.add(Json.object("range", Json.range(source.text(), edit.start(), edit.end()),
                        "newText", edit.text()));
            changes.add(Json.object("textDocument", Json.object("uri", edits.file().toUri()
                    .toString(), "version", versions.get(edits.file())), "edits", values));
        }
        for (FileOperation operation : plan.fileOperations()) {
            String uri = operation.path().toUri().toString();

            switch (operation.kind()) {
                case DELETE -> changes.add(Json.object("kind", "delete", "uri", uri, "options", Json
                        .object("recursive", false, "ignoreIfNotExists", false)));
                case MOVE -> {
                    if (plan.before().sources().stream().anyMatch(s -> s.file().equals(operation
                            .destination())))
                        throw new RpcServer.RpcError(-32803,
                                "Cyclic or overwriting moves require the native workspace transaction adapter");
                    changes.add(Json.object("kind", "rename", "oldUri", uri, "newUri", operation
                            .destination().toUri().toString(), "options", Json.object("overwrite",
                            false, "ignoreIfExists", false)));
                }
                case CREATE -> {
                    changes.add(Json.object("kind", "create", "uri", uri, "options", Json
                            .object("overwrite", false, "ignoreIfExists", false)));
                    changes.add(Json.object("textDocument", Json.object("uri", uri, "version",
                            null), "edits", List.of(Json.object("range", Json.range("", 0, 0),
                            "newText", operation.content()))));
                }
            }
        }
        return Json.object("documentChanges", changes);
    }

    private SourceSnapshot source(JsonObject params) {
        return session.snapshot().source(file(Json.string(params.getAsJsonObject("textDocument"),
                "uri")));
    }

    private Location location(JsonObject params, SourceSnapshot source) {
        return new Location(source.file(), offset(source.text(), params
                .getAsJsonObject("position")));
    }

    private JsonObject location(Location location) {
        SourceSnapshot source = session.snapshot().source(location.file());
        return Json.object("uri", location.file().toUri().toString(), "range", Json.range(source
                .text(), location.start(), location.end()));
    }

    private Path file(String uri) {
        URI parsed = URI.create(uri);

        if (!"file".equals(parsed.getScheme()))
            throw new IllegalArgumentException("Only local file URIs are accepted");
        return Json.safePath(allowedRoot, Path.of(parsed).toString());
    }

    private static int offset(String source, JsonObject position) {
        return Json.offset(source, Json.integer(position, "line"), Json.integer(position,
                "character"));
    }

    private static int symbolKind(String kind) {
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "method" -> 6;
            case "field" -> 8;
            case "interface" -> 11;
            case "enum" -> 10;
            case "package" -> 4;
            case "constructor" -> 9;
            case "local", "parameter", "variable" -> 13;
            default -> 5;
        };
    }

    @Override
    public void close() {
        if (session != null) {
            session.close();
            session = null;
        }
    }
}
