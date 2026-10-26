package dev.turboj.project;

import dev.turboj.refactorj.api.Model;
import dev.turboj.refactorj.api.Operation;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import javax.lang.model.SourceVersion;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;

/**
 * What an explorer item is and the plain file operations on it. A folder inside a Java source root is a package and
 * a {@code .java} file there is a compilation unit: renaming, moving or deleting those is RefactorJ's work, which keeps
 * references, package declarations and imports right. Everything else is files and folders, moved or deleted as one
 * workspace transaction so that Undo brings it back.
 */
public final class ProjectFiles {
    public enum Kind { JAVA_FILE, FILE, PACKAGE, SOURCE_ROOT, FOLDER }

    /** An explorer item; {@code sourceRoot} is the Java source folder it lies in, or null. */
    public record Node(Path path, Kind kind, Path sourceRoot) {
        public boolean directory() { return kind == Kind.PACKAGE || kind == Kind.SOURCE_ROOT || kind == Kind.FOLDER; }

        /** The package a folder is, or the package a file lies in; "" for the default package. */
        public String packageName() {
            if (sourceRoot == null) return "";
            Path folder = kind == Kind.PACKAGE || kind == Kind.SOURCE_ROOT ? path : path.getParent();
            return ProjectFiles.packageName(sourceRoot, folder);
        }

        /** The folder new items go into. */
        public Path folder() { return directory() ? path : path.getParent(); }
    }

    /** Text files up to this size move through a workspace transaction; larger or binary files are moved directly. */
    private static final long TRANSACTION_LIMIT = 8L << 20;

    private ProjectFiles() {}

    public static Node classify(Path path, boolean directory, List<Path> javaRoots) {
        Path absolute = path.toAbsolutePath().normalize();
        Path root = javaRoots.stream().map(r -> r.toAbsolutePath().normalize()).filter(absolute::startsWith)
                .max(Comparator.comparingInt(Path::getNameCount)).orElse(null);
        if (root == null) return new Node(absolute, directory ? Kind.FOLDER : Kind.FILE, null);
        if (absolute.equals(root)) return new Node(absolute, Kind.SOURCE_ROOT, root);
        if (directory) return validPackage(packageName(root, absolute)) ? new Node(absolute, Kind.PACKAGE, root) : new Node(absolute, Kind.FOLDER, root);
        String name = absolute.getFileName().toString();
        boolean javaUnit = name.endsWith(".java") && validPackage(packageName(root, absolute.getParent()));
        return new Node(absolute, javaUnit ? Kind.JAVA_FILE : Kind.FILE, root);
    }

    public static String packageName(Path sourceRoot, Path folder) {
        Path relative = sourceRoot.relativize(folder);
        if (relative.toString().isEmpty()) return "";
        var parts = new ArrayList<String>();
        relative.forEach(part -> parts.add(part.toString()));
        return String.join(".", parts);
    }

    public static Path packageFolder(Path sourceRoot, String packageName) {
        return packageName.isEmpty() ? sourceRoot : sourceRoot.resolve(packageName.replace('.', '/'));
    }

    private static boolean validPackage(String name) { return name.isEmpty() || packageProblem(name) == null; }

    /** Why {@code name} cannot name a file or folder here, or null. */
    public static String fileNameProblem(String name) {
        if (name == null || name.isBlank()) return "Enter a name";
        if (!name.equals(name.strip())) return "A name cannot start or end with a space";
        if (name.equals(".") || name.equals("..")) return "Not a valid name";
        if (name.contains("/") || name.contains("\\") || name.contains(":") || name.chars().anyMatch(c -> c < 32))
            return "A name cannot contain / \\ : or control characters";
        if (name.getBytes(StandardCharsets.UTF_8).length > 255) return "Name too long";
        return null;
    }

    /** Why {@code name} is not a Java package name, or null. */
    public static String packageProblem(String name) {
        if (name == null || name.isBlank()) return "Enter a package name";
        for (String part : name.split("\\.", -1)) {
            if (part.isEmpty()) return "Empty segment in " + name;
            if (!SourceVersion.isIdentifier(part)) return "'" + part + "' is not a Java identifier";
            if (SourceVersion.isKeyword(part)) return "'" + part + "' is a Java keyword";
        }
        return null;
    }

    /** Why {@code name} is not a Java type name, or null. */
    public static String typeProblem(String name) {
        if (name == null || name.isBlank()) return "Enter a class name";
        if (!SourceVersion.isIdentifier(name)) return "'" + name + "' is not a Java identifier";
        if (SourceVersion.isKeyword(name) || name.equals("var") || name.equals("record") || name.equals("yield"))
            return "'" + name + "' is reserved";
        return null;
    }

    /** The name of the top-level type a file declares under its own name: {@code Calculator} in Calculator.java. */
    public static Optional<Model.Location> primaryType(Path file, String source) {
        String expected = file.getFileName().toString().replaceFirst("\\.java$", "");
        for (Object type : parse(source).types())
            if (type instanceof AbstractTypeDeclaration declaration && declaration.getName().getIdentifier().equals(expected)) {
                var name = declaration.getName();
                return Optional.of(new Model.Location(file, name.getStartPosition(), name.getStartPosition() + name.getLength()));
            }
        return Optional.empty();
    }

    /** The name in a file's {@code package} declaration. */
    public static Optional<Model.Location> packageDeclaration(Path file, String source) {
        var unit = parse(source);
        if (unit.getPackage() == null) return Optional.empty();
        var name = unit.getPackage().getName();
        return Optional.of(new Model.Location(file, name.getStartPosition(), name.getStartPosition() + name.getLength()));
    }

    private static CompilationUnit parse(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        parser.setResolveBindings(false);
        var options = new HashMap<String, String>();
        JavaCore.setComplianceOptions("25", options);
        parser.setCompilerOptions(options);
        return (CompilationUnit) parser.createAST(null);
    }

    /**
     * The packages under {@code folder} (itself included) that hold compilation units, without those inside another
     * one of them: renaming each with its subpackages renames the whole tree once.
     */
    public static List<Path> packageTrees(Path folder) throws IOException {
        var withSources = new ArrayList<Path>();
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path directory : paths.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).sorted().toList()) {
                if (withSources.stream().anyMatch(directory::startsWith)) continue;
                try (Stream<Path> children = Files.list(directory)) {
                    if (children.anyMatch(child -> child.getFileName().toString().endsWith(".java") && Files.isRegularFile(child)))
                        withSources.add(directory);
                }
            }
        }
        return withSources;
    }

    /** Every regular file under {@code folder}, or the file itself. */
    public static List<Path> files(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return List.of(path);
        try (Stream<Path> paths = Files.walk(path)) {
            return paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
        }
    }

    /** Whether every file can travel in a workspace transaction: UTF-8 text of reasonable size. */
    public static boolean allText(List<Path> files) throws IOException {
        for (Path file : files) if (text(file) == null) return false;
        return true;
    }

    private static String text(Path file) throws IOException {
        if (Files.size(file) > TRANSACTION_LIMIT) return null;
        byte[] bytes = Files.readAllBytes(file);
        for (byte b : bytes) if (b == 0) return null;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException binary) {
            return null;
        }
    }

    /**
     * Moves {@code from} (a file or a folder with all it contains) to {@code to}, as one transaction. Every file must
     * be text ({@link #allText}).
     */
    public static Model.Plan movePlan(Path root, Path from, Path to, String description) throws IOException {
        var moves = new LinkedHashMap<Path, Path>();
        for (Path file : files(from)) moves.put(file, file.equals(from) ? to : to.resolve(from.relativize(file)));
        var operations = moves.entrySet().stream()
                .map(move -> new Model.FileOperation(Model.FileOperationKind.MOVE, move.getKey(), move.getValue(), null)).toList();
        return plan(root, Operation.MOVE, moves.keySet().stream().toList(), operations, description);
    }

    /** Deletes {@code path} (a file or a folder with all it contains), as one transaction of text files. */
    public static Model.Plan deletePlan(Path root, Path path, String description) throws IOException {
        var files = files(path);
        var operations = files.stream().map(file -> new Model.FileOperation(Model.FileOperationKind.DELETE, file, null, null)).toList();
        return plan(root, Operation.SAFE_DELETE, files, operations, description);
    }

    private static Model.Plan plan(Path root, Operation operation, List<Path> files, List<Model.FileOperation> operations, String description)
            throws IOException {
        var sources = new ArrayList<Model.SourceSnapshot>();
        for (Path file : files) {
            String text = text(file);
            if (text == null) throw new IOException(file.getFileName() + " is not a text file");
            sources.add(new Model.SourceSnapshot(file, text, 0, false));
        }
        var before = new Model.WorkspaceSnapshot(root, 0, sources, List.of(), List.of(), List.of());
        return new Model.Plan(UUID.randomUUID().toString(), operation, before, List.of(), List.of(), operations, List.of(),
                Model.ValidationStatus.VALID, description);
    }

    /**
     * Removes the folders a move or delete left empty, from {@code start} upward, never {@code stop} or above it nor
     * a Java source root.
     */
    public static void pruneEmpty(Path start, Path stop, List<Path> javaRoots) {
        for (Path folder = start; folder != null && folder.startsWith(stop) && !folder.equals(stop) && !javaRoots.contains(folder);
                folder = folder.getParent()) {
            try (Stream<Path> children = Files.list(folder)) {
                if (children.findAny().isPresent()) return;
            } catch (IOException gone) {
                continue;
            }
            try {
                Files.delete(folder);
            } catch (IOException kept) {
                return;
            }
        }
    }

    /** Empty folders under {@code folder}, deepest first, then {@code folder} itself if it ends up empty. */
    public static void pruneTree(Path folder) {
        if (!Files.isDirectory(folder)) return;
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path directory : paths.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList()) {
                try (Stream<Path> children = Files.list(directory)) {
                    if (children.findAny().isEmpty()) Files.delete(directory);
                }
            }
        } catch (IOException kept) {
            // A folder that cannot be removed stays; nothing in it was lost.
        }
    }

    /** {@code Calculator.java} → {@code Calculator}; other names unchanged. */
    public static String baseName(Path file) {
        String name = file.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(".java") ? name.substring(0, name.length() - 5) : name;
    }

    /** The project's packages in a source root, for a destination list. */
    public static List<String> packages(Path sourceRoot) throws IOException {
        var names = new ArrayList<String>();
        names.add("");
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            for (Path directory : paths.filter(Files::isDirectory).filter(p -> !p.equals(sourceRoot)).sorted().toList()) {
                String name = packageName(sourceRoot, directory);
                if (validPackage(name)) names.add(name);
            }
        }
        return names;
    }
}
