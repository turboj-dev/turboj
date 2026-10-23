package com.turboj.ui.themes;

import com.google.gson.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Local metadata index and six-item, lazy palette pages. All I/O belongs to one bounded worker. */
public final class ThemeCatalog implements AutoCloseable {
    public static final int PAGE_SIZE = 6, MAX_ENTRIES = 10_000, CACHE_LIMIT = 24;

    public record Entry(
            String key,
            String id,
            String name,
            String resourceFile,
            Path localPath,
            String previewBackground) {
        public Entry(String key, String id, String name, String resourceFile, Path localPath) {
            this(key, id, name, resourceFile, localPath, "#808080");
        }

        public boolean bundled() {
            return resourceFile != null;
        }
    }

    public record Preview(Entry entry, Theme theme, String problem) {}

    public record Page(
            long requestId,
            String query,
            int page,
            int total,
            int selected,
            List<Preview> previews,
            int invalidFiles,
            boolean truncated,
            String problem,
            int firstIndex) {
        public Page {
            previews = List.copyOf(previews);
        }
    }

    public record Statistics(
            int indexedEntries,
            int cachedPalettes,
            int pendingRequests,
            boolean scanning,
            int indexScans) {}

    public record WheelResult(
            long requestId,
            String query,
            int total,
            ThemeWheelPlan plan,
            Preview target,
            List<Preview> prepared,
            String problem) {
        public WheelResult {
            prepared = List.copyOf(prepared);
        }
    }

    public record PreviewBatch(long requestId, List<Preview> previews) {
        public PreviewBatch {
            previews = List.copyOf(previews);
        }
    }

    private record PageRequest(long id, String query, int page, String preferred, boolean rows) {}

    private record Request(long id, Runnable work) {}

    private record Cached(long modified, long size, Theme theme) {}

    private final Path directory;
    private volatile Path externalSelection;
    private long directoryStamp = Long.MIN_VALUE;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "turboj-theme-catalog");
                        t.setDaemon(true);
                        return t;
                    });
    private final Object lock = new Object();
    private final LinkedHashMap<String, Cached> palettes =
            new LinkedHashMap<>(32, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                    return size() > CACHE_LIMIT;
                }
            };
    private Request pending;
    private volatile boolean closed, scanning;
    private boolean draining, refreshRequested;
    private long sequence;
    private volatile int indexSize, cacheSize, indexScans;
    private List<Entry> metadata;
    private int invalidFiles;
    private boolean truncated;
    private String indexProblem;

    public ThemeCatalog(Path directory, Path externalSelection) {
        this.directory = directory.toAbsolutePath().normalize();
        this.externalSelection =
                externalSelection == null ? null : externalSelection.toAbsolutePath().normalize();
    }

    public ThemeCatalog(Path directory) {
        this(directory, null);
    }

    public static String bundledKey(String id) {
        return "bundled:" + Theme.normalizeId(id);
    }

    public static String localKey(Path file) {
        return "file:" + file.toAbsolutePath().normalize();
    }

    public long request(String query, int page, String preferred, Consumer<Page> callback) {
        return request(query, page, preferred, false, callback);
    }

    /** A virtual two-row window. Only six palettes are loaded, regardless of total catalog size. */
    public long requestWindow(
            String query, int firstRow, String preferred, Consumer<Page> callback) {
        return request(query, firstRow, preferred, true, callback);
    }

    private long request(
            String query, int page, String preferred, boolean rows, Consumer<Page> callback) {
        String filter = query == null ? "" : query.strip();
        return enqueue(
                id -> {
                    Page result;
                    try {
                        result = build(new PageRequest(id, filter, page, preferred, rows));
                    } catch (Exception | LinkageError failure) {
                        result =
                                new Page(
                                        id,
                                        filter,
                                        0,
                                        0,
                                        0,
                                        List.of(),
                                        invalidFiles,
                                        truncated,
                                        failure.getMessage(),
                                        0);
                    }
                    Page delivery = result;
                    deliver(id, () -> callback.accept(delivery));
                });
    }

    /** The winner is uniform across the complete filtered metadata, not only the visible page. */
    public long requestRandom(String query, Consumer<WheelResult> callback) {
        String filter = query == null ? "" : query.strip();
        return enqueue(
                id -> {
                    WheelResult result;
                    try {
                        List<Entry> matches = matches(filter);
                        ThemeWheelPlan plan =
                                ThemeWheelPlan.create(matches, ThreadLocalRandom.current());
                        Preview target =
                                new Preview(
                                        plan.target().entry(), load(plan.target().entry()), null);
                        List<Preview> prepared = new ArrayList<>();
                        for (int i = 0; i < Math.min(7, plan.frames().size()); i++)
                            prepared.add(preview(plan.frames().get(i).entry()));
                        result =
                                new WheelResult(
                                        id, filter, matches.size(), plan, target, prepared, null);
                    } catch (Exception | LinkageError failure) {
                        result =
                                new WheelResult(
                                        id, filter, 0, null, null, List.of(), failure.getMessage());
                    }
                    WheelResult delivery = result;
                    deliver(id, () -> callback.accept(delivery));
                });
    }

    /** At most the next six palettes are warmed for a drum already in motion. */
    public long prefetch(List<Entry> entries, Consumer<PreviewBatch> callback) {
        List<Entry> batch = List.copyOf(entries.stream().limit(6).toList());
        return enqueue(
                id -> {
                    List<Preview> previews = new ArrayList<>();
                    for (Entry entry : batch) {
                        if (closed) break;
                        previews.add(preview(entry));
                    }
                    deliver(id, () -> callback.accept(new PreviewBatch(id, previews)));
                });
    }

    private long enqueue(java.util.function.LongConsumer work) {
        synchronized (lock) {
            if (closed) return -1;
            long id = ++sequence;
            pending = new Request(id, () -> work.accept(id));
            if (!draining) {
                draining = true;
                executor.execute(this::drain);
            }
            return id;
        }
    }

    private void deliver(long id, Runnable callback) {
        synchronized (lock) {
            if (closed || id != sequence) return;
        }
        callback.run();
    }

    private Preview preview(Entry entry) {
        try {
            return new Preview(entry, load(entry), null);
        } catch (IOException | RuntimeException failure) {
            return new Preview(entry, null, failure.getMessage());
        }
    }

    /**
     * Closing a browser cancels its results but retains the per-window index and bounded palette
     * cache.
     */
    public void cancelPending() {
        synchronized (lock) {
            sequence++;
            pending = null;
        }
    }

    public void includeExternal(Path path) {
        Path normalized = path == null ? null : path.toAbsolutePath().normalize();
        if (!Objects.equals(externalSelection, normalized)) {
            externalSelection = normalized;
            refresh();
        }
    }

    private long directoryStamp() {
        try {
            return Files.getLastModifiedTime(directory).toMillis();
        } catch (IOException failure) {
            return -1;
        }
    }

    public void refresh() {
        synchronized (lock) {
            refreshRequested = true;
        }
    }

    public Statistics statistics() {
        synchronized (lock) {
            return new Statistics(
                    indexSize, cacheSize, pending == null ? 0 : 1, scanning, indexScans);
        }
    }

    private void drain() {
        while (true) {
            Request request;
            synchronized (lock) {
                if (closed || pending == null) {
                    draining = false;
                    return;
                }
                request = pending;
                pending = null;
                if (refreshRequested) {
                    metadata = null;
                    palettes.clear();
                    cacheSize = 0;
                    refreshRequested = false;
                }
            }
            try {
                request.work.run();
            } catch (RuntimeException ignored) {
                /* A faulty callback must not strand the bounded worker. */
            }
        }
    }

    private List<Entry> matches(String query) throws IOException {
        if (metadata == null || directoryStamp() != directoryStamp) index();
        String needle = query.toLowerCase(Locale.ROOT);
        return metadata.stream()
                .filter(
                        e ->
                                needle.isBlank()
                                        || e.name.toLowerCase(Locale.ROOT).contains(needle)
                                        || e.id.toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    private Page build(PageRequest request) throws IOException {
        List<Entry> matches = matches(request.query);
        int selected = -1;
        if (request.preferred != null)
            for (int i = 0; i < matches.size(); i++)
                if (matches.get(i).key.equals(request.preferred)) {
                    selected = i;
                    break;
                }
        int unit = request.rows ? 3 : PAGE_SIZE;
        int position =
                request.page < 0 && selected >= 0 ? selected / unit : Math.max(0, request.page);
        int maximum =
                request.rows
                        ? Math.max(0, (matches.size() + 2) / 3 - 2)
                        : Math.max(0, (matches.size() - 1) / PAGE_SIZE);
        position = Math.min(position, maximum);
        int first = position * unit;
        if (selected < first || selected >= first + PAGE_SIZE) selected = first;
        List<Preview> previews = new ArrayList<>(PAGE_SIZE);
        for (int i = first; i < Math.min(matches.size(), first + PAGE_SIZE); i++) {
            if (closed) break;
            Entry entry = matches.get(i);
            try {
                previews.add(new Preview(entry, load(entry), null));
            } catch (IOException | RuntimeException failure) {
                previews.add(new Preview(entry, null, failure.getMessage()));
            }
        }
        return new Page(
                request.id,
                request.query,
                first / PAGE_SIZE,
                matches.size(),
                selected,
                previews,
                invalidFiles,
                truncated,
                indexProblem,
                first);
    }

    private void index() throws IOException {
        scanning = true;
        indexScans++;
        invalidFiles = 0;
        truncated = false;
        indexProblem = null;
        try {
            List<Entry> all = new ArrayList<>();
            for (var item : Theme.bundledResources().entrySet()) {
                if (closed) break;
                try {
                    JsonObject definition =
                            JsonParser.parseString(Theme.resourceJson(item.getValue()))
                                    .getAsJsonObject();
                    String name = metadataName(definition);
                    all.add(
                            new Entry(
                                    "bundled:" + item.getKey(),
                                    item.getKey(),
                                    name,
                                    item.getValue(),
                                    null,
                                    metadataBackground(definition)));
                } catch (IOException | RuntimeException failure) {
                    invalidFiles++;
                }
            }
            if (Files.isDirectory(directory))
                try (var files = Files.newDirectoryStream(directory, "*.json")) {
                    int examined = all.size();
                    for (Path file : files) {
                        if (closed) break;
                        if (examined++ >= MAX_ENTRIES) {
                            truncated = true;
                            break;
                        }
                        addLocal(all, file);
                    }
                } catch (IOException | DirectoryIteratorException failure) {
                    indexProblem = "Local themes unavailable: " + failure.getMessage();
                }
            if (all.size() < MAX_ENTRIES
                    && externalSelection != null
                    && all.stream().noneMatch(e -> externalSelection.equals(e.localPath)))
                addLocal(all, externalSelection);
            List<String> primary = List.of(Theme.BUNDLED);
            all.sort(
                    Comparator.comparingInt(
                                    (Entry e) ->
                                            e.bundled() && primary.contains(e.id)
                                                    ? primary.indexOf(e.id)
                                                    : primary.size())
                            .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(Entry::key));
            metadata = List.copyOf(all);
            indexSize = metadata.size();
            directoryStamp = directoryStamp();
        } finally {
            scanning = false;
        }
    }

    private void addLocal(List<Entry> all, Path file) {
        try {
            if (!Files.isRegularFile(file)) return;
            Path path = file.toAbsolutePath().normalize();
            String json;
            try (var input = Files.newInputStream(path)) {
                json = Theme.readDefinition(input);
            }
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            String name = metadataName(object);
            String id =
                    object.get("schemaVersion").getAsInt() == 1
                            ? "custom"
                            : object.get("id").getAsString();
            if (!id.matches("[a-z][a-z0-9_-]{0,63}"))
                throw new IllegalArgumentException("Invalid theme id");
            all.add(new Entry(localKey(path), id, name, null, path, metadataBackground(object)));
        } catch (IOException | RuntimeException failure) {
            invalidFiles++;
        }
    }

    private static String metadataBackground(JsonObject definition) {
        try {
            JsonObject surface =
                    definition.has("ui")
                            ? definition.getAsJsonObject("ui")
                            : definition.getAsJsonObject("editor");
            String color = surface.get("background").getAsString();
            return color.matches("#[0-9a-fA-F]{6}") ? color : "#808080";
        } catch (RuntimeException malformed) {
            return "#808080";
        }
    }

    private static String metadataName(String json) {
        return metadataName(JsonParser.parseString(json).getAsJsonObject());
    }

    private static String metadataName(JsonObject object) {
        int schema = object.get("schemaVersion").getAsInt();
        if (schema != 1 && schema != 2)
            throw new IllegalArgumentException("Unsupported theme schema");
        String name = object.get("name").getAsString();
        if (name.isBlank() || name.length() > 120)
            throw new IllegalArgumentException("Invalid theme name");
        return name;
    }

    private Theme load(Entry entry) throws IOException {
        long modified = 0, size = 0;
        if (entry.localPath != null) {
            var attributes =
                    Files.readAttributes(
                            entry.localPath, java.nio.file.attribute.BasicFileAttributes.class);
            modified = attributes.lastModifiedTime().toMillis();
            size = attributes.size();
        }
        Cached cached = palettes.get(entry.key);
        if (cached != null && cached.modified == modified && cached.size == size)
            return cached.theme;
        Theme theme =
                entry.bundled()
                        ? Theme.parse(Theme.resourceJson(entry.resourceFile))
                        : Theme.load(entry.localPath);
        if (entry.bundled() && !theme.id().equals(entry.id))
            throw new IOException("Theme catalog identity mismatch");
        palettes.put(entry.key, new Cached(modified, size, theme));
        cacheSize = palettes.size();
        return theme;
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            pending = null;
        }
        executor.shutdown();
    }
}
