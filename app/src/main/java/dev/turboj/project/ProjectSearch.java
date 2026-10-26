package dev.turboj.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.*;

/**
 * Keyboard search over a project's files and Java declarations. Declarations come from JDT syntax trees only:
 * no build path or semantic workspace is needed, so it works before any file is open, and unsaved buffers count.
 */
public final class ProjectSearch {
    public enum Kind { CLASS, FILE, METHOD, FIELD }
    /** name is what a query matches, label what is shown; offset is the declaration's name in file (0 for files). */
    public record Item(Kind kind, String name, String label, String detail, Path file, int offset) {}

    private static final int MAX_FILES = 50_000, MAX_SOURCE_BYTES = 2_000_000;
    /** Build output folders, skipped only beside a build file so that a package named "build" is still indexed. */
    private static final Set<String> OUTPUT = Set.of("target", "build", "out", "bin");
    private static final List<String> BUILD_FILES = List.of("pom.xml", "build.gradle", "build.gradle.kts", ".turboj");
    private record Cached(long modified, long size, List<Item> items) {}
    private final Map<Path, Cached> parsed = new HashMap<>();

    /** Walks root on the calling worker thread. overlays are unsaved editor buffers by path. */
    public synchronized List<Item> scan(Path root, Map<Path, String> overlays) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        var files = new ArrayList<Path>();
        Files.walkFileTree(base, EnumSet.noneOf(FileVisitOption.class), 32, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (directory.equals(base)) return FileVisitResult.CONTINUE;
                String name = directory.getFileName().toString();
                boolean output = OUTPUT.contains(name) && BUILD_FILES.stream().anyMatch(marker -> Files.exists(directory.resolveSibling(marker)));
                return name.startsWith(".") || name.equals("node_modules") || output ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile() && !file.getFileName().toString().startsWith(".")) files.add(file);
                return files.size() >= MAX_FILES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException failure) { return FileVisitResult.CONTINUE; }
        });
        var items = new ArrayList<Item>();
        for (Path file : files) {
            Path folder = base.relativize(file).getParent();
            String name = file.getFileName().toString();
            items.add(new Item(Kind.FILE, name, name, folder == null ? "" : folder.toString(), file, 0));
            if (name.endsWith(".java")) items.addAll(declarations(file, overlays.get(file)));
        }
        parsed.keySet().retainAll(new HashSet<>(files));
        return List.copyOf(items);
    }

    private List<Item> declarations(Path file, String overlay) {
        if (overlay != null) return parse(file, overlay);
        try {
            var attributes = Files.readAttributes(file, BasicFileAttributes.class);
            long modified = attributes.lastModifiedTime().toMillis();
            Cached cached = parsed.get(file);
            if (cached != null && cached.modified() == modified && cached.size() == attributes.size()) return cached.items();
            List<Item> items = attributes.size() > MAX_SOURCE_BYTES ? List.of()
                    : parse(file, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            parsed.put(file, new Cached(modified, attributes.size(), items));
            return items;
        } catch (IOException | RuntimeException unreadable) { return List.of(); }
    }

    /** Classes (with nested types), methods and fields of one source; bodies are not searched. */
    static List<Item> parse(Path file, String source) {
        ASTParser parser = ASTParser.newParser(AST.JLS25);
        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.latestSupportedJavaVersion(), options);
        parser.setCompilerOptions(options);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        CompilationUnit unit = (CompilationUnit) parser.createAST(null);
        String pkg = unit.getPackage() == null ? "" : unit.getPackage().getName().getFullyQualifiedName();
        var items = new ArrayList<Item>();
        unit.accept(new ASTVisitor() {
            private final List<String> types = new ArrayList<>();
            private boolean enter(AbstractTypeDeclaration type) {
                String outer = String.join(".", types);
                String where = pkg.isEmpty() ? outer.isEmpty() ? "(default package)" : outer : outer.isEmpty() ? pkg : pkg + "." + outer;
                items.add(new Item(Kind.CLASS, type.getName().getIdentifier(), type.getName().getIdentifier(), where, file,
                        type.getName().getStartPosition()));
                types.add(type.getName().getIdentifier());
                return true;
            }
            private void exit() { types.removeLast(); }
            private String owner() { return types.isEmpty() ? "" : types.getLast(); }
            @Override public boolean visit(TypeDeclaration type) { return enter(type); }
            @Override public void endVisit(TypeDeclaration type) { exit(); }
            @Override public boolean visit(EnumDeclaration type) { return enter(type); }
            @Override public void endVisit(EnumDeclaration type) { exit(); }
            @Override public boolean visit(RecordDeclaration type) { return enter(type); }
            @Override public void endVisit(RecordDeclaration type) { exit(); }
            @Override public boolean visit(AnnotationTypeDeclaration type) { return enter(type); }
            @Override public void endVisit(AnnotationTypeDeclaration type) { exit(); }
            @Override public boolean visit(MethodDeclaration method) {
                if (!method.isConstructor() && !types.isEmpty()) {
                    String parameters = ((List<?>) method.parameters()).stream().map(SingleVariableDeclaration.class::cast)
                            .map(parameter -> parameter.getType() + (parameter.isVarargs() ? "..." : "")).collect(Collectors.joining(", "));
                    String name = method.getName().getIdentifier();
                    items.add(new Item(Kind.METHOD, name, name + "(" + parameters + ")"
                            + (method.getReturnType2() == null ? "" : " : " + method.getReturnType2()), owner(), file,
                            method.getName().getStartPosition()));
                }
                return false; // Local and anonymous classes inside bodies are not project declarations.
            }
            @Override public boolean visit(FieldDeclaration field) {
                for (Object fragment : field.fragments()) {
                    SimpleName name = ((VariableDeclarationFragment) fragment).getName();
                    items.add(new Item(Kind.FIELD, name.getIdentifier(), name.getIdentifier() + " : " + field.getType(), owner(), file,
                            name.getStartPosition()));
                }
                return false;
            }
            @Override public boolean visit(EnumConstantDeclaration constant) {
                items.add(new Item(Kind.FIELD, constant.getName().getIdentifier(), constant.getName().getIdentifier(), owner(), file,
                        constant.getName().getStartPosition()));
                return false;
            }
            @Override public boolean visit(Initializer initializer) { return false; }
        });
        return items;
    }

    /**
     * Best first: exact, prefix, CamelHump (CT or CalcTe for CalculatorTest), then substring; at equal quality and kind the
     * current file's declarations lead, which with an empty query lists the current file first, like a quick outline.
     */
    public static List<Item> search(List<Item> items, Set<Kind> kinds, String query, Path current, int limit) {
        String text = query.strip();
        record Ranked(Item item, int score) {}
        return items.stream().filter(item -> kinds.contains(item.kind())).map(item -> new Ranked(item, score(item, text)))
                .filter(ranked -> ranked.score() >= 0)
                .sorted(Comparator.comparingInt(Ranked::score)
                        .thenComparingInt(ranked -> ranked.item().kind().ordinal())
                        .thenComparing(ranked -> !ranked.item().file().equals(current))
                        .thenComparingInt(ranked -> ranked.item().name().length())
                        .thenComparing(ranked -> ranked.item().name(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(ranked -> ranked.item().file()))
                .limit(limit).map(Ranked::item).toList();
    }

    private static int score(Item item, String query) {
        int score = score(item.name(), query);
        if (score >= 0 || query.isEmpty()) return score;
        // demo.Calc or src/main finds a class by package or a file by folder.
        String qualified = item.kind() == Kind.FILE ? item.detail() + "/" + item.name()
                : item.kind() == Kind.CLASS ? item.detail() + "." + item.name() : "";
        return (query.contains(".") || query.contains("/"))
                && qualified.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)) ? 4 : -1;
    }

    /** Lower is better; -1 does not match. */
    static int score(String name, String query) {
        if (query.isEmpty()) return 3;
        String lowerName = name.toLowerCase(Locale.ROOT), lowerQuery = query.toLowerCase(Locale.ROOT);
        if (lowerName.equals(lowerQuery)) return 0;
        if (lowerName.startsWith(lowerQuery)) return 1;
        if (humps(name, wordStarts(name), 0, query, 0)) return 2;
        return lowerName.contains(lowerQuery) ? 3 : -1;
    }

    private static List<Integer> wordStarts(String name) {
        var starts = new ArrayList<Integer>();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i), previous = i == 0 ? ' ' : name.charAt(i - 1);
            if (!Character.isLetterOrDigit(c)) continue;
            if (i == 0 || !Character.isLetterOrDigit(previous) || Character.isUpperCase(c) && !Character.isUpperCase(previous)
                    || Character.isDigit(c) != Character.isDigit(previous)) starts.add(i);
        }
        return starts;
    }

    /** Each query part is a prefix of a later word: CalcTe → Calc·ulator Te·st. */
    private static boolean humps(String name, List<Integer> starts, int word, String query, int consumed) {
        if (consumed == query.length()) return true;
        for (int w = word; w < starts.size(); w++) {
            int start = starts.get(w), end = w + 1 < starts.size() ? starts.get(w + 1) : name.length();
            for (int length = 1; length <= end - start && consumed + length <= query.length(); length++) {
                if (Character.toLowerCase(name.charAt(start + length - 1)) != Character.toLowerCase(query.charAt(consumed + length - 1))) break;
                if (humps(name, starts, w + 1, query, consumed + length)) return true;
            }
        }
        return false;
    }
}
