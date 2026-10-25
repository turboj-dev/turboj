package dev.turboj.testing.intelligence;

import static dev.turboj.testing.intelligence.LineIntelligence.*;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.*;

/**
 * Resolves reported JUnit identities to declarations in the run's hashed source inventory.
 * This is source navigation evidence, never proof of executed bytecode or production coverage.
 * Call from a worker: it reads files and parses Java, and does not inspect unsaved editor buffers.
 */
public final class TestSourceResolver {
    private static final int MAX_FILES = 4096, MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final long MAX_SCAN_BYTES = 32L * 1024 * 1024, MAX_CACHE_WEIGHT = 8L * 1024 * 1024;
    private static final String EVIDENCE_PREFIX = "JDT test declaration; source SHA-256 ";
    private static final Set<String> TEST_ANNOTATIONS = Set.of(
            "org.junit.Test", "org.junit.jupiter.api.Test", "org.junit.jupiter.api.RepeatedTest",
            "org.junit.jupiter.api.TestFactory", "org.junit.jupiter.api.TestTemplate",
            "org.junit.jupiter.params.ParameterizedTest");
    private record Key(Path file, String hash) {}
    private record Declaration(String binaryType, String canonicalType, String method, int line, boolean junit) {}
    private record Parsed(List<Declaration> declarations, int weight, boolean valid) {}
    private record Location(Path file, String hash, Declaration declaration) {}
    private static final Map<Key, Parsed> CACHE = new LinkedHashMap<>(16, .75f, true);
    private static long cacheWeight;

    private TestSourceResolver() {}

    /**
     * Unresolvable, changed, ambiguous, inherited, or unsupported names remain unattributed.
     * Any incomplete Java inventory fails closed because it could conceal a duplicate type.
     * JUnit annotation imports must be explicit or fully qualified; wildcard and composed
     * annotations require classpath resolution and are deliberately not guessed here.
     */
    public static Run resolve(Run run) throws IOException {
        Objects.requireNonNull(run);
        List<TestResult> original = run.tests();
        if (original.isEmpty() || run.root() == null || run.sourceHashes().isEmpty()) return run;
        Set<String> requested = new HashSet<>();
        for (TestResult test : original)
            if (test.freshness() != Freshness.STALE && validIdentity(test.name())) requested.add(test.name());
        if (requested.isEmpty()) return withoutResolvedLocations(run);
        Map<String, List<Location>> index = new HashMap<>();
        Path realRoot;
        try { realRoot = run.root().toRealPath(); }
        catch (NoSuchFileException missing) { return withoutResolvedLocations(run); }
        List<Map.Entry<Path, String>> sources = run.sourceHashes().entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getKey().toString().endsWith(".java"))
                .sorted(Comparator.comparing(entry -> entry.getKey().toString())).toList();
        if (sources.size() > MAX_FILES) return withoutResolvedLocations(run);
        long bytesRead = 0;
        for (var entry : sources) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Test source resolution cancelled");
            Path file = run.root().resolve(entry.getKey()).toAbsolutePath().normalize();
            String expectedHash = entry.getValue();
            if (!file.startsWith(run.root()) || expectedHash == null || !expectedHash.matches("[a-fA-F0-9]{64}"))
                return withoutResolvedLocations(run);
            byte[] bytes;
            try {
                if (!Files.isRegularFile(file) || !file.toRealPath().startsWith(realRoot)) return withoutResolvedLocations(run);
                try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(MAX_FILE_BYTES + 1); }
            } catch (NoSuchFileException | AccessDeniedException unavailable) { return withoutResolvedLocations(run); }
            bytesRead += bytes.length;
            // An incomplete scan cannot establish uniqueness across the source inventory.
            if (bytes.length > MAX_FILE_BYTES || bytesRead > MAX_SCAN_BYTES) return withoutResolvedLocations(run);
            String text;
            try { text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
            catch (CharacterCodingException unsupportedEncoding) { return withoutResolvedLocations(run); }
            String hash = sha256(text);
            if (!hash.equalsIgnoreCase(expectedHash)) return withoutResolvedLocations(run);
            Parsed parsed = parsed(file, hash, text, bytes.length);
            if (!parsed.valid()) return withoutResolvedLocations(run);
            for (Declaration declaration : parsed.declarations()) {
                Location location = new Location(file, hash, declaration);
                add(index, requested, declaration.binaryType() + "::" + declaration.method(), location);
                if (!declaration.canonicalType().equals(declaration.binaryType()))
                    add(index, requested, declaration.canonicalType() + "::" + declaration.method(), location);
            }
        }
        List<TestResult> resolved = new ArrayList<>(original.size());
        for (TestResult test : original) {
            List<Association> associations = retained(test);
            List<Location> candidates = index.getOrDefault(test.name(), List.of());
            if (test.freshness() != Freshness.STALE && candidates.size() == 1 && candidates.getFirst().declaration().junit()) {
                Location location = candidates.getFirst();
                boolean alreadyReported = associations.stream().anyMatch(a -> a.kind() == Attribution.TEST_DECLARATION
                        && a.file().equals(location.file()) && a.line() == location.declaration().line());
                if (!alreadyReported) associations.add(new Association(location.file(), location.declaration().line(),
                        Attribution.TEST_DECLARATION, EVIDENCE_PREFIX + location.hash()
                                + "; exact declared JUnit identity " + test.name()
                                + "; executed bytecode identity unavailable; no production-line attribution"));
            }
            resolved.add(withAssociations(test, associations));
        }
        return withTests(run, resolved);
    }

    private static void add(Map<String, List<Location>> index, Set<String> requested, String identity, Location location) {
        if (requested.contains(identity)) index.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(location);
    }

    private static boolean validIdentity(String name) {
        int separator = name.indexOf("::");
        if (separator <= 0 || separator != name.lastIndexOf("::")) return false;
        String type = name.substring(0, separator), method = name.substring(separator + 2);
        return Arrays.stream(type.split("\\.", -1)).allMatch(TestSourceResolver::identifier) && identifier(method);
    }

    private static boolean identifier(String value) {
        if (value.isEmpty() || !Character.isJavaIdentifierStart(value.codePointAt(0))) return false;
        for (int offset = Character.charCount(value.codePointAt(0)); offset < value.length();) {
            int cp = value.codePointAt(offset);
            if (!Character.isJavaIdentifierPart(cp)) return false;
            offset += Character.charCount(cp);
        }
        return true;
    }

    private static Parsed parsed(Path file, String hash, String text, int bytes) {
        Key key = new Key(file, hash);
        synchronized (CACHE) { Parsed cached = CACHE.get(key); if (cached != null) return cached; }
        ASTParser parser = ASTParser.newParser(AST.JLS25);
        Map<String, String> options = new HashMap<>(); JavaCore.setComplianceOptions("25", options);
        parser.setCompilerOptions(options); parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setUnitName(file.getFileName().toString()); parser.setSource(text.toCharArray());
        parser.setResolveBindings(false); parser.setStatementsRecovery(false);
        CompilationUnit unit = (CompilationUnit) parser.createAST(null);
        List<Declaration> declarations = new ArrayList<>();
        boolean valid = Arrays.stream(unit.getProblems()).noneMatch(problem -> problem.isError());
        if (valid) {
            String packageName = unit.getPackage() == null ? "" : unit.getPackage().getName().getFullyQualifiedName() + ".";
            Imports imports = new Imports(unit);
            for (Object value : unit.types()) if (value instanceof AbstractTypeDeclaration type)
                collect(unit, type, packageName, packageName, imports, declarations);
        }
        Parsed result = new Parsed(List.copyOf(declarations), Math.max(1, bytes), valid);
        synchronized (CACHE) {
            Parsed prior = CACHE.put(key, result); cacheWeight += result.weight() - (prior == null ? 0 : prior.weight());
            Iterator<Parsed> oldest = CACHE.values().iterator();
            while (CACHE.size() > 128 || cacheWeight > MAX_CACHE_WEIGHT) { cacheWeight -= oldest.next().weight(); oldest.remove(); }
        }
        return result;
    }

    private static void collect(CompilationUnit unit, AbstractTypeDeclaration type, String binaryPrefix,
            String canonicalPrefix, Imports imports, List<Declaration> declarations) {
        if (type instanceof AnnotationTypeDeclaration) return;
        String binary = binaryPrefix + type.getName().getIdentifier(), canonical = canonicalPrefix + type.getName().getIdentifier();
        boolean junit3 = type instanceof TypeDeclaration declaration && declaration.getSuperclassType() != null
                && imports.isType(declaration.getSuperclassType().toString(), "junit.framework.TestCase");
        for (Object value : type.bodyDeclarations()) {
            if (value instanceof MethodDeclaration method && !method.isConstructor()) {
                boolean annotated = method.modifiers().stream().anyMatch(modifier -> modifier instanceof Annotation annotation
                        && TEST_ANNOTATIONS.stream().anyMatch(known -> imports.isType(annotation.getTypeName().getFullyQualifiedName(), known)));
                boolean conventional = junit3 && method.getName().getIdentifier().startsWith("test")
                        && method.parameters().isEmpty() && Modifier.isPublic(method.getModifiers())
                        && !Modifier.isStatic(method.getModifiers()) && method.getReturnType2() instanceof PrimitiveType primitive
                        && primitive.getPrimitiveTypeCode() == PrimitiveType.VOID;
                int line = unit.getLineNumber(method.getName().getStartPosition());
                if (line > 0) declarations.add(new Declaration(binary, canonical, method.getName().getIdentifier(), line,
                        (annotated || conventional) && !Modifier.isAbstract(method.getModifiers())
                                && !Modifier.isStatic(method.getModifiers()) && !Modifier.isPrivate(method.getModifiers())
                                && method.getBody() != null));
            } else if (value instanceof AbstractTypeDeclaration nested)
                collect(unit, nested, binary + "$", canonical + ".", imports, declarations);
        }
    }

    private static final class Imports {
        private final Map<String, Set<String>> explicit = new HashMap<>();
        private final Set<String> declared = new HashSet<>();
        Imports(CompilationUnit unit) {
            for (Object item : unit.imports()) {
                ImportDeclaration value = (ImportDeclaration) item;
                if (value.isStatic() || value.isOnDemand()) continue;
                String name = value.getName().getFullyQualifiedName();
                explicit.computeIfAbsent(name.substring(name.lastIndexOf('.') + 1), ignored -> new HashSet<>()).add(name);
            }
            unit.accept(new ASTVisitor() {
                @Override public void preVisit(ASTNode node) {
                    if (node instanceof AbstractTypeDeclaration type) declared.add(type.getName().getIdentifier());
                }
            });
        }
        boolean isType(String written, String known) {
            if (written.equals(known)) return !declared.contains(written.substring(0, written.indexOf('.')));
            if (written.indexOf('.') >= 0 || declared.contains(written)) return false;
            Set<String> names = explicit.get(written);
            if (names != null) return names.size() == 1 && names.contains(known);
            // An on-demand import may be shadowed by an unparsed same-package type.
            // Without a classpath, declining it is safer than inventing JUnit identity.
            return false;
        }
    }

    private static List<Association> retained(TestResult test) {
        return new ArrayList<>(test.associations().stream().filter(a -> !a.evidence().startsWith(EVIDENCE_PREFIX)).toList());
    }
    private static Run withoutResolvedLocations(Run run) {
        return withTests(run, run.tests().stream().map(test -> withAssociations(test, retained(test))).toList());
    }
    private static TestResult withAssociations(TestResult test, List<Association> associations) {
        if (test.associations().equals(associations)) return test;
        return new TestResult(test.id(), test.runId(), test.suite(), test.name(), test.project(), test.module(), test.packageName(),
                test.phase(), test.runtime(), test.invocation(), test.status(), test.freshness(), test.durationMillis(), test.failure(), associations);
    }
    private static Run withTests(Run run, List<TestResult> tests) {
        if (tests.equals(run.tests())) return run;
        return new Run(run.id(), run.root(), run.state(), tests, run.coverage(), run.sourceHashes(), run.message(),
                run.htmlReport(), run.sequence(), run.coverageAvailability());
    }
}
