package dev.turboj.refactorj.api;

import java.nio.file.Path;
import java.util.*;

/** Immutable public values. All offsets are UTF-16 and ranges are half open. */
public final class Model {
    private Model() {}
    public record Location(Path file, int start, int end) {
        public Location {
            file = Objects.requireNonNull(file).toAbsolutePath().normalize();
            if (start < 0 || end < start) throw new IllegalArgumentException("Invalid source range");
        }
        public Location(Path file, int offset) { this(file, offset, offset); }
    }
    public record SourceSnapshot(Path file, String text, long version, boolean open) {
        public SourceSnapshot { file = Objects.requireNonNull(file).toAbsolutePath().normalize(); Objects.requireNonNull(text); }
    }
    /** One compilation boundary (for example a Maven module's main or test source set). */
    public record JavaProjectConfiguration(String id, List<Path> sourceRoots, List<Path> classpath,
            List<Path> modulePath, List<String> dependencies, String release, String source,
            String target, Path javaHome, String encoding, boolean preview) {
        public JavaProjectConfiguration {
            if(id==null||id.isBlank())throw new IllegalArgumentException("Missing project id");
            sourceRoots=sourceRoots.stream().map(p->p.toAbsolutePath().normalize()).toList();
            classpath=List.copyOf(classpath);modulePath=List.copyOf(modulePath);dependencies=List.copyOf(dependencies);
            release=Objects.requireNonNullElse(release,"");source=Objects.requireNonNullElse(source,"");target=Objects.requireNonNullElse(target,"");encoding=Objects.requireNonNullElse(encoding,"UTF-8");
            if(release.isBlank()&&source.isBlank())throw new IllegalArgumentException("Missing project language level");
        }
        public String languageLevel(){return release.isBlank()?source:release;}
    }
    public record WorkspaceSnapshot(Path root, long version, List<SourceSnapshot> sources,
                                    List<Path> sourceRoots, List<Path> classpath,
                                    List<JavaProjectConfiguration> projects) {
        public WorkspaceSnapshot(Path root,long version,List<SourceSnapshot> sources,List<Path> sourceRoots,List<Path> classpath){this(root,version,sources,sourceRoots,classpath,List.of());}
        public WorkspaceSnapshot {
            root = Objects.requireNonNull(root).toAbsolutePath().normalize();
            sources = sources.stream().sorted(Comparator.comparing(s -> s.file().toString())).toList();
            sourceRoots = List.copyOf(sourceRoots); classpath = List.copyOf(classpath);
            projects=List.copyOf(projects);Set<String> ids=new HashSet<>();for(var project:projects)if(!ids.add(project.id()))throw new IllegalArgumentException("Duplicate Java project id");
            for(var project:projects)if(!ids.containsAll(project.dependencies()))throw new IllegalArgumentException("Unknown Java project dependency");
            Set<Path> seen = new HashSet<>();
            for (var source : sources) if (!source.file().startsWith(root) || !seen.add(source.file()))
                throw new IllegalArgumentException("Duplicate or out-of-root source");
        }
        public SourceSnapshot source(Path file) {
            Path normalized = file.toAbsolutePath().normalize();
            return sources.stream().filter(s -> s.file().equals(normalized)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Source is outside this snapshot: " + normalized));
        }
    }
    public record Symbol(String id, String name, String kind, String signature, Location declaration) {}
    public record Hierarchy(Symbol type, List<Symbol> supertypes, List<Symbol> subtypes) {
        public Hierarchy { supertypes = List.copyOf(supertypes); subtypes = List.copyOf(subtypes); }
    }
    public enum Severity { INFO, WARNING, ERROR, FATAL }
    public record Diagnostic(Severity severity, String code, String message, Location location) {
        public Diagnostic { Objects.requireNonNull(severity); Objects.requireNonNull(code); Objects.requireNonNull(message); }
    }
    public record TextEdit(int start, int end, String text) {
        public TextEdit { if (start < 0 || end < start) throw new IllegalArgumentException("Invalid edit"); Objects.requireNonNull(text); }
        public static String apply(String source, List<TextEdit> edits) {
            StringBuilder output = new StringBuilder(); int offset = 0;
            for (var edit : edits) {
                if (edit.start < offset || edit.end > source.length()) throw new IllegalArgumentException("Unordered/overlapping/out-of-range edits");
                output.append(source, offset, edit.start).append(edit.text); offset = edit.end;
            }
            return output.append(source, offset, source.length()).toString();
        }
    }
    public record FileEdits(Path file, List<TextEdit> edits) {
        public FileEdits { file = file.toAbsolutePath().normalize(); edits = List.copyOf(edits); }
    }
    public enum FileOperationKind { CREATE, DELETE, MOVE }
    /** CREATE uses path/content, MOVE uses path/destination; DELETE uses path only. */
    public record FileOperation(FileOperationKind kind, Path path, Path destination, String content) {
        public FileOperation {
            Objects.requireNonNull(kind); path = Objects.requireNonNull(path).toAbsolutePath().normalize();
            if (destination != null) destination = destination.toAbsolutePath().normalize();
            if (kind == FileOperationKind.MOVE && destination == null) throw new IllegalArgumentException("Missing destination");
            if (kind == FileOperationKind.CREATE && content == null) throw new IllegalArgumentException("Missing created content");
        }
    }
    public enum ValidationStatus { VALID, CONFLICTED, CANCELLED }
    public record Plan(String id, Operation operation, WorkspaceSnapshot before, List<Symbol> affectedSymbols,
                       List<FileEdits> edits, List<FileOperation> fileOperations,
                       List<Diagnostic> diagnostics, ValidationStatus status, String description) {
        public Plan {
            Objects.requireNonNull(id); Objects.requireNonNull(operation); Objects.requireNonNull(before);
            affectedSymbols = List.copyOf(affectedSymbols); edits = List.copyOf(edits);
            fileOperations = List.copyOf(fileOperations); diagnostics = List.copyOf(diagnostics);
            Objects.requireNonNull(status); Objects.requireNonNull(description);
        }
        public boolean valid() { return status == ValidationStatus.VALID && diagnostics.stream().noneMatch(d -> d.severity().ordinal() >= Severity.ERROR.ordinal()); }
    }
    public record Descriptor(Operation operation, String title, boolean applicable, List<Diagnostic> diagnostics) {
        public Descriptor { diagnostics = List.copyOf(diagnostics); }
    }
    public record Validation(boolean valid, List<Diagnostic> diagnostics) {
        public Validation { diagnostics = List.copyOf(diagnostics); }
    }
}
