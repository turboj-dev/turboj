package dev.turboj.refactorj.jdt;

import dev.turboj.refactorj.api.JavaIntelligence;
import dev.turboj.refactorj.api.JavaStructure;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.module.ModuleFinder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/**
 * Completion, navigation, documentation and signatures borrowing the authoritative RefactorJ Java model.
 * No second project index, remote requests or user-code execution.
 */
public final class SharedJavaIntelligence implements JavaIntelligence {
    private static final String MARKER = "__turboj_completion__";
    private final Path scratch;
    private final LtkWorkspace workspace;
    private final Map<Path, String> staged = new HashMap<>();
    private long stagedWorkspaceVersion=Long.MIN_VALUE;
    private final Map<String, Path> sourceIdentities = new HashMap<>();
    private final LinkedHashMap<String, Parsed> parses = bounded(12);
    private final LinkedHashMap<String, Resolved> symbols = bounded(256);
    private final LinkedHashMap<String, Located> locations = bounded(96);
    private final Map<Path, List<String>> archiveEntries = new HashMap<>();
    private final Map<Path, Long> observedSources = new HashMap<>();
    private final LinkedHashMap<String, ITypeBinding> typeBindings = bounded(128);
    private List<String> platformTypes;
    private boolean closed;

    @Override public JavaStructure.Range selectStructure(Context context, int start, int end, JavaStructure.Selection selection) {
        return new JdtKeyboardEditing(workspace, context).select(start, end, selection);
    }
    @Override public JavaStructure.EditResult editStructure(Context context, int caret, int anchor, JavaStructure.Edit edit) {
        return new JdtKeyboardEditing(workspace, context).edit(caret, anchor, edit);
    }
    @Override public List<JavaStructure.Target> navigationTargets(Context context, int offset, JavaStructure.Navigation navigation, String query) {
        return new JdtKeyboardEditing(workspace, context).navigate(offset, navigation, query);
    }
    @Override public dev.turboj.refactorj.api.Requests.ChangeSignature parameterMove(Context context, int offset, int direction) {
        return new JdtKeyboardEditing(workspace, context).parameterMove(offset, direction);
    }

    private record Parsed(Context context, CompilationUnit unit, String source) {}

    private record Resolved(
            Context context, Parsed parsed, IBinding binding, SimpleName reference) {}

    private record Located(Declaration declaration, ASTNode node, String source) {}

    private record Candidate(IBinding binding, String insertion, int caret, int score) {}

    private record Prefix(int start, int end, String value, boolean member) {}

    public SharedJavaIntelligence(LtkWorkspace workspace) {
        this.workspace = workspace;
        try {
            scratch = Files.createTempDirectory("turboj-java-");

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static <K, V> LinkedHashMap<K, V> bounded(int limit) {
        return new LinkedHashMap<>(16, .75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > limit;
            }
        };
    }

    @Override
    public TestTarget testTarget(Context context, int offset) {
        Parsed parsed = parse(context, context.source(), "source");
        ASTNode node = NodeFinder.perform(parsed.unit(), Math.max(0, Math.min(offset, context.source().length())), 0);
        MethodDeclaration method = null;
        AbstractTypeDeclaration type = null;
        for (ASTNode current = node; current != null; current = current.getParent()) {
            if (method == null && current instanceof MethodDeclaration declaration) method = declaration;
            if (current instanceof AbstractTypeDeclaration declaration) { type = declaration; break; }
        }
        if (type == null) type = primaryType(parsed.unit(), context.file());
        if (type == null) return null;
        ITypeBinding binding = type.resolveBinding();
        if (binding == null || binding.isRecovered() || binding.getBinaryName() == null) return null;
        if (method == null || method.isConstructor()) return new TestTarget(binding.getBinaryName(), null, "");
        IMethodBinding methodBinding = method.resolveBinding();
        if (methodBinding == null || methodBinding.isRecovered()) return new TestTarget(binding.getBinaryName(), null, "");
        String parameters = Arrays.stream(methodBinding.getParameterTypes()).map(t -> t.getErasure().getQualifiedName()).collect(java.util.stream.Collectors.joining(","));
        return new TestTarget(binding.getBinaryName(), method.getName().getIdentifier(), parameters);
    }

    @Override public boolean isTestClass(Context context, int offset) {
        Parsed parsed = parse(context, context.source(), "source");
        ASTNode node = NodeFinder.perform(parsed.unit(), Math.max(0, Math.min(offset, context.source().length())), 0);
        AbstractTypeDeclaration type = null;
        for (ASTNode current = node; current != null; current = current.getParent())
            if (current instanceof AbstractTypeDeclaration declaration) { type = declaration; break; }
        if (type == null) type = primaryType(parsed.unit(), context.file());
        ITypeBinding binding = type == null ? null : type.resolveBinding();
        return binding != null && !binding.isRecovered() && !binding.isInterface() && !Modifier.isAbstract(binding.getModifiers())
                && hasJUnitTests(binding, new HashSet<>());
    }

    @Override public boolean hasMainMethod(Context context) {
        Parsed parsed = parse(context, context.source(), "source");
        String target = workspace.javaProject(context.file()).getOption(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, true);
        boolean extended = JavaCore.compareJavaVersions(target, JavaCore.VERSION_25) >= 0;
        var types = new ArrayList<ITypeBinding>();
        parsed.unit().accept(new ASTVisitor() { @Override public void preVisit(ASTNode node) {
            if (node instanceof AbstractTypeDeclaration declaration) {
                ITypeBinding binding = declaration.resolveBinding();
                if (binding != null && !binding.isRecovered()) types.add(binding);
            }
        } });
        return types.stream().anyMatch(type -> hasMainCandidate(type, type, extended,
                new HashSet<>(), new HashSet<>(), new ArrayList<>()));
    }

    /** JLS 12.1.4 candidates, respecting real method types and Java member inheritance.
     * Constructor/initialization failure and erroneous source remain compiler/launcher concerns. */
    private static boolean hasMainCandidate(ITypeBinding type, ITypeBinding initial, boolean extended,
            Set<String> visited, Set<String> signatures, List<String> descendants) {
        if (type == null || type.isRecovered() || !visited.add(type.getKey())) return false;
        String ownerPackage = type.getPackage() == null ? "" : type.getPackage().getName();
        boolean inherited = !type.isEqualTo(initial);
        for (IMethodBinding method : type.getDeclaredMethods()) {
            if (method.isConstructor() || method.isRecovered() || !method.getName().equals("main")) continue;
            int modifiers = method.getModifiers();
            if (inherited && (Modifier.isPrivate(modifiers) || type.isInterface() && Modifier.isStatic(modifiers))) continue;
            if (inherited && !Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers)
                    && descendants.stream().anyMatch(pkg -> !pkg.equals(ownerPackage))) continue;
            ITypeBinding[] parameters = method.getParameterTypes();
            String signature = Arrays.stream(parameters).map(p -> p.getErasure().getQualifiedName())
                    .collect(java.util.stream.Collectors.joining(",", "(", ")"));
            if (!signatures.add(signature) || Modifier.isPrivate(modifiers) || Modifier.isAbstract(modifiers)) continue;
            ITypeBinding result = method.getReturnType();
            if (result == null || !result.isPrimitive() || !result.getName().equals("void")) continue;
            boolean strings = parameters.length == 1 && parameters[0].isArray() && parameters[0].getDimensions() == 1
                    && parameters[0].getElementType().getQualifiedName().equals("java.lang.String");
            if (extended ? parameters.length == 0 || strings : strings && Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers)) return true;
        }
        var path = new ArrayList<>(descendants); path.add(ownerPackage);
        if (hasMainCandidate(type.getSuperclass(), initial, extended, visited, signatures, path)) return true;
        var interfaces = new ArrayList<>(Arrays.asList(type.getInterfaces()));
        interfaces.sort((a, b) -> a.isSubTypeCompatible(b) ? -1 : b.isSubTypeCompatible(a) ? 1 : 0);
        for (ITypeBinding parent : interfaces)
            if (hasMainCandidate(parent, initial, extended, visited, signatures, path)) return true;
        return false;
    }

    private static AbstractTypeDeclaration primaryType(CompilationUnit tree, Path file) {
        String name = file.getFileName().toString().replaceFirst("\\.java$", "");
        for (Object value : tree.types()) if (value instanceof AbstractTypeDeclaration type && type.getName().getIdentifier().equals(name)) return type;
        return tree.types().size() == 1 && tree.types().getFirst() instanceof AbstractTypeDeclaration type ? type : null;
    }

    private static final Set<String> JUNIT_TEST_ANNOTATIONS = Set.of("org.junit.Test", "org.junit.jupiter.api.Test",
            "org.junit.jupiter.api.TestFactory", "org.junit.jupiter.api.TestTemplate", "org.junit.jupiter.api.RepeatedTest",
            "org.junit.jupiter.params.ParameterizedTest");

    private static boolean hasJUnitTests(ITypeBinding type, Set<String> visited) {
        if (type == null || type.isRecovered() || !visited.add(type.getKey())) return false;
        if ("junit.framework.TestCase".equals(type.getQualifiedName())) return true;
        for (IMethodBinding method : type.getDeclaredMethods()) {
            if (method.isConstructor() || Modifier.isPrivate(method.getModifiers()) || Modifier.isStatic(method.getModifiers())
                    || Modifier.isAbstract(method.getModifiers())) continue;
            for (IAnnotationBinding annotation : method.getAnnotations())
                if (junitAnnotation(annotation.getAnnotationType(), new HashSet<>())) return true;
        }
        for (ITypeBinding nested : type.getDeclaredTypes()) {
            if (Arrays.stream(nested.getAnnotations()).anyMatch(a -> a.getAnnotationType().getQualifiedName().equals("org.junit.jupiter.api.Nested"))
                    && hasJUnitTests(nested, visited)) return true;
        }
        if (hasJUnitTests(type.getSuperclass(), visited)) return true;
        for (ITypeBinding parent : type.getInterfaces()) if (hasJUnitTests(parent, visited)) return true;
        return false;
    }

    private static boolean junitAnnotation(ITypeBinding annotation, Set<String> visited) {
        if (annotation == null || annotation.isRecovered() || !visited.add(annotation.getKey())) return false;
        if (JUNIT_TEST_ANNOTATIONS.contains(annotation.getQualifiedName())) return true;
        for (IAnnotationBinding meta : annotation.getAnnotations()) if (junitAnnotation(meta.getAnnotationType(), visited)) return true;
        return false;
    }

    @Override
    public CompletionResult complete(Context context, int offset) {
        return complete(context, offset, false);
    }
    @Override public CompletionResult completeSmart(Context context, int offset) {
        return complete(context, offset, true);
    }
    private CompletionResult complete(Context context, int offset, boolean smart) {
        long started = System.nanoTime();
        if (offset < 0 || offset > context.source().length()) return emptyCompletion(started);
        Prefix prefix = prefix(context.source(), offset);
        if (!isCode(context.source(), offset) || (!prefix.member() && prefix.value().isEmpty()))
            return emptyCompletion(started);
        String after = context.source().substring(prefix.end()).stripLeading();
        String inserted = MARKER + (after.startsWith("(") ? "" : "()");
        String probe =
                context.source().substring(0, prefix.start())
                        + inserted
                        + context.source().substring(prefix.end());
        Parsed parsed = parse(context, probe, "completion");
        SimpleName marker = nameAt(parsed.unit(), prefix.start());
        if (marker == null || !MARKER.equals(marker.getIdentifier())) {
            if (!prefix.member())
                return new CompletionResult(
                        typeCompletions(context, prefix), System.nanoTime() - started);
            return emptyCompletion(started);
        }
        ITypeBinding enclosing = enclosingType(marker);
        ITypeBinding receiver = receiver(marker);
        boolean staticOnly = receiverIsType(marker);
        List<Candidate> candidates = new ArrayList<>();
        if (prefix.member()) {
            if (receiver == null || receiver.isRecovered()) return emptyCompletion(started);
            addMembers(receiver, enclosing, staticOnly, candidates, new HashSet<>());
        } else {
            addMembers(enclosing, enclosing, inStaticMethod(marker), candidates, new HashSet<>());
            addLocals(parsed.unit(), marker, prefix.start(), candidates);
            addTypes(parsed.unit(), candidates);
        }
        // A semantic declaration is the only source of a proposal. No keyword/string dictionary.
        Map<String, Candidate> applicable = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            String key = proposalIdentity(candidate.binding());
            Candidate prior = applicable.get(key);
            if (prior == null || candidate.score() < prior.score()) applicable.put(key, candidate);
        }
        candidates = new ArrayList<>(applicable.values());
        if (smart) {
            ITypeBinding expected = expectedType(marker);
            if (expected == null || expected.isRecovered()) return emptyCompletion(started);
            candidates.removeIf(candidate -> !compatible(candidate.binding(), expected));
        }
        Map<String, Completion> unique = new LinkedHashMap<>();
        candidates.sort(
                Comparator.comparingInt(Candidate::score)
                        .thenComparing(c -> c.binding().getName())
                        .thenComparing(c -> signature(c.binding())));
        for (Candidate candidate : candidates) {
            IBinding binding = candidate.binding();
            if (!binding.getName().startsWith(prefix.value()) || binding.isRecovered()) continue;
            String text = candidate.insertion();
            int caret = candidate.caret();
            if (after.startsWith("(") && binding instanceof IMethodBinding) {
                text = binding.getName();
                caret = text.length();
            }
            if (text.equals(prefix.value()) && prefix.end() == offset) continue;
            Completion proposal =
                    new Completion(
                            prefix.start(),
                            prefix.end(),
                            text,
                            caret,
                            signature(binding),
                            owner(binding),
                            List.of());
            unique.putIfAbsent(proposalIdentity(binding), proposal);
            if (unique.size() == 160) break;
        }
        if (!prefix.member() && !smart)
            for (Completion type : typeCompletions(context, prefix))
                unique.putIfAbsent("type:" + type.detail(), type);
        return new CompletionResult(List.copyOf(unique.values()), System.nanoTime() - started);
    }

    private static boolean compatible(IBinding binding, ITypeBinding expected) {
        ITypeBinding value = binding instanceof IVariableBinding variable ? variable.getType()
                : binding instanceof IMethodBinding method ? method.getReturnType() : null;
        return value != null && !value.isRecovered() && value.isAssignmentCompatible(expected);
    }
    private static ITypeBinding expectedType(ASTNode marker) {
        for (ASTNode node = marker; node != null; node = node.getParent()) {
            ASTNode parent = node.getParent();
            if (parent instanceof VariableDeclarationFragment variable && variable.getInitializer() == node) {
                var binding = variable.resolveBinding(); return binding == null ? null : binding.getType();
            }
            if (parent instanceof Assignment assignment && assignment.getRightHandSide() == node) return assignment.getLeftHandSide().resolveTypeBinding();
            if (parent instanceof ReturnStatement) {
                for (ASTNode owner = parent; owner != null; owner = owner.getParent()) {
                    if (owner instanceof LambdaExpression lambda) { var binding = lambda.resolveMethodBinding(); return binding == null ? null : binding.getReturnType(); }
                    if (owner instanceof MethodDeclaration method) { var binding = method.resolveBinding(); return binding == null ? null : binding.getReturnType(); }
                }
            }
            if (parent instanceof MethodInvocation invocation && invocation.arguments().contains(node)) {
                var binding = invocation.resolveMethodBinding(); int index = invocation.arguments().indexOf(node);
                return binding == null || binding.isRecovered() || index >= binding.getParameterTypes().length ? null : binding.getParameterTypes()[index];
            }
        }
        return null;
    }

    @Override
    public Symbol resolveSymbol(Context context, int offset) {
        if (offset < 0 || offset > context.source().length()) return null;
        Parsed parsed = parse(context, context.source(), "source");
        SimpleName name = nameAt(parsed.unit(), offset);
        if (name == null && offset > 0) name = nameAt(parsed.unit(), offset - 1);
        if (name == null) return null;
        IBinding binding = selectedBinding(name);
        if (binding == null || binding.isRecovered() || binding.getKey() == null) return null;
        // A problem on the reference means overload recovery may have picked an arbitrary
        // candidate.
        for (var problem : parsed.unit().getProblems()) {
            if (problem.isError()
                    && problem.getSourceStart() <= name.getStartPosition()
                    && problem.getSourceEnd() >= name.getStartPosition()
                    && (problem.getMessage().contains("ambiguous")
                            || problem.getMessage().contains("not applicable"))) return null;
        }
        binding = declarationBinding(binding);
        Symbol symbol =
                new Symbol(
                        binding.getKey(),
                        binding.getName(),
                        owner(binding),
                        signature(binding),
                        kind(binding),
                        name.getStartPosition(),
                        name.getStartPosition() + name.getLength());
        symbols.put(
                symbolCacheKey(context, symbol.key()),
                new Resolved(context, parsed, binding, name));
        return symbol;
    }

    @Override
    public Declaration findDeclaration(Context context, Symbol symbol) {
        Located located = locate(context, symbol);
        return located == null
                ? new Declaration(
                        null,
                        null,
                        0,
                        0,
                        true,
                        "Source is not attached. Showing the resolved declaration signature.")
                : located.declaration();
    }

    @Override
    public Documentation documentation(Context context, Symbol symbol) {
        Located located = locate(context, symbol);
        String text = "";
        String origin = "Source is not attached. Signature resolved by Eclipse JDT.";
        String signature = symbol.signature();
        if (located != null) {
            origin = located.declaration().origin();
            Javadoc doc = javadoc(located.node());
            if (doc != null)
                text =
                        plainJavadoc(
                                located.source()
                                        .substring(
                                                doc.getStartPosition(),
                                                doc.getStartPosition() + doc.getLength()));
            Resolved resolved = resolved(context, symbol);
            if (resolved != null
                    && resolved.binding() instanceof IMethodBinding method
                    && located.node() instanceof MethodDeclaration declaration)
                signature = methodSignature(method, declaration);
            if (text.isBlank()
                    && resolved != null
                    && resolved.binding() instanceof IMethodBinding method) {
                String inherited = inheritedDocumentation(context, method, new HashSet<>());
                if (!inherited.isBlank()) text = inherited;
            }
        }
        if (text.isBlank()) text = "No Javadoc is available for this declaration.";
        return new Documentation(symbol.owner(), signature, text, origin, located != null);
    }

    @Override
    public SignatureHelp signatureHelp(Context context, int offset) {
        long started = System.nanoTime();
        if (offset < 0 || offset > context.source().length() || !isCode(context.source(), offset))
            return null;
        int open = invocationOpen(context.source(), offset);
        if (open < 0) return null;
        Parsed parsed = parse(context, context.source(), "source");
        ASTNode invocation = invocationAt(parsed.unit(), open);
        if (invocation == null) {
            // Complete only the active unfinished invocation for JDT recovery. Caller source is
            // untouched.
            String probe =
                    context.source().substring(0, offset)
                            + "null)"
                            + context.source().substring(offset);
            parsed = parse(context, probe, "signature");
            invocation = invocationAt(parsed.unit(), open);
        }
        if (invocation == null) return null;
        IMethodBinding selected = null;
        ITypeBinding receiver = null;
        String methodName = null;
        boolean constructors = false;
        if (invocation instanceof MethodInvocation method) {
            selected = method.resolveMethodBinding();
            methodName = method.getName().getIdentifier();
            receiver =
                    method.getExpression() == null
                            ? enclosingType(method)
                            : method.getExpression().resolveTypeBinding();
        } else if (invocation instanceof ClassInstanceCreation creation) {
            selected = creation.resolveConstructorBinding();
            receiver = creation.getType().resolveBinding();
            constructors = true;
        } else if (invocation instanceof SuperMethodInvocation method) {
            selected = method.resolveMethodBinding();
            methodName = method.getName().getIdentifier();
            ITypeBinding enclosing = enclosingType(method);
            receiver = enclosing == null ? null : enclosing.getSuperclass();
        }
        if (receiver == null || receiver.isRecovered()) return null;
        List<IMethodBinding> methods = new ArrayList<>();
        collectMethods(receiver, methods, new HashSet<>());
        final String name = methodName;
        final boolean ctors = constructors;
        final IMethodBinding chosen = selected;
        final ASTNode activeInvocation = invocation;
        List<String> signatures =
                methods.stream()
                        .filter(
                                m ->
                                        ctors
                                                ? m.isConstructor()
                                                        && m.getDeclaringClass()
                                                                .isEqualTo(
                                                                        receiverType(
                                                                                activeInvocation))
                                                : !m.isConstructor() && m.getName().equals(name))
                        .filter(m -> visible(m, enclosingType(activeInvocation)))
                        .sorted(
                                Comparator.comparingInt(
                                        m ->
                                                chosen != null
                                                                && m.getMethodDeclaration()
                                                                        .isEqualTo(
                                                                                chosen
                                                                                        .getMethodDeclaration())
                                                        ? 0
                                                        : 1))
                        .map(SharedJavaIntelligence::signature)
                        .distinct()
                        .limit(24)
                        .toList();
        if (signatures.isEmpty()) return null;
        return new SignatureHelp(
                signatures,
                argumentIndex(activeInvocation, context.source(), open + 1, offset),
                open,
                System.nanoTime() - started);
    }

    private CompletionResult emptyCompletion(long started) {
        return new CompletionResult(List.of(), System.nanoTime() - started);
    }

    private Parsed parse(Context context, String source, String purpose) {
        ensureOpen();
        stage(context);
        String key =
                context.file()
                        + "|"
                        + context.version()
                        + "|"
                        + context.projectVersion()
                        + "|"
                        + context.openSources().hashCode()
                        + "|"
                        + context.classpath().hashCode()
                        + "|"
                        + purpose
                        + "|"
                        + source;
        sourceFilesChanged();
        Parsed cached = parses.get(key);
        if (cached != null) return cached;
        CompilationUnit tree;
        if (source.equals(context.source())) {
            tree = workspace.ast(context.file());
        } else {
            var parser = parser();
            parser.setProject(workspace.javaProject(context.file()));
            parser.setCompilerOptions(workspace.javaProject(context.file()).getOptions(true));
            parser.setResolveBindings(true);
            parser.setBindingsRecovery(true);
            parser.setSource(source.toCharArray());
            parser.setUnitName(workspace.unit(context.file()).getPath().toString());
            tree = (CompilationUnit) parser.createAST(null);
        }
        Parsed result = new Parsed(context, tree, source);
        observeSourceBindings(result);
        parses.put(key, result);
        return result;
    }

    private void observeSourceBindings(Parsed parsed) {
        Set<String> owners = new HashSet<>();
        parsed.unit()
                .accept(
                        new ASTVisitor() {
                            @Override
                            public boolean visit(SimpleName name) {
                                IBinding binding = name.resolveBinding();
                                ITypeBinding type = declaringType(binding);
                                if (binding instanceof IVariableBinding variable && type == null)
                                    type = variable.getType();
                                if (type != null && type.isFromSource() && !type.isRecovered())
                                    owners.add(
                                            topLevel(type).getQualifiedName().replace('.', '/')
                                                    + ".java");
                                return true;
                            }
                        });
        for (String relative : owners) {
            // An open in-memory buffer remains authoritative even if its disk file changes.
            if (sourceIdentities.containsKey(relative)) continue;
            for (Path root : parsed.context().sourceRoots()) {
                Path file = root.resolve(relative).normalize();
                if (!Files.isRegularFile(file)) continue;
                try {
                    observedSources.put(file, Files.getLastModifiedTime(file).toMillis());
                } catch (IOException ignored) {
                }
                break;
            }
        }
    }

    private static ASTParser parser() {
        ASTParser parser = ASTParser.newParser(AST.JLS25);
        Map<String, String> options = new HashMap<>();
        JavaCore.setComplianceOptions(JavaCore.VERSION_25, options);
        options.put(JavaCore.COMPILER_PB_ENABLE_PREVIEW_FEATURES, JavaCore.DISABLED);
        options.put(JavaCore.COMPILER_DOC_COMMENT_SUPPORT, JavaCore.ENABLED);
        parser.setCompilerOptions(options);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setStatementsRecovery(true);
        return parser;
    }

    private static CompilationUnit syntax(String source) {
        ASTParser parser = parser();
        parser.setSource(source.toCharArray());
        return (CompilationUnit) parser.createAST(null);
    }

    private static String unitName(String source, String fallback) {
        CompilationUnit unit = syntax(source);
        String file = fallback;
        for (Object item : unit.types())
            if (item instanceof AbstractTypeDeclaration type
                    && !(type instanceof ImplicitTypeDeclaration)) {
                if (file.equals(fallback) || Modifier.isPublic(type.getModifiers()))
                    file = type.getName() + ".java";
                if (Modifier.isPublic(type.getModifiers())) break;
            }
        return (unit.getPackage() == null
                        ? ""
                        : unit.getPackage().getName().getFullyQualifiedName().replace('.', '/')
                                + "/")
                + file;
    }

    private void stage(Context context) {
        sourceIdentities.clear();
        for (var source : workspace.snapshot().sources()) {
            String relative = unitName(source.text(), source.file().getFileName().toString());
            sourceIdentities.put(relative, source.file());
        }
        Map<Path,String> current = new HashMap<>();
        for (var source : workspace.snapshot().sources()) current.put(source.file(), source.text());
        if (!staged.equals(current) || stagedWorkspaceVersion!=workspace.snapshot().version()) {
            parses.clear(); symbols.clear(); locations.clear(); typeBindings.clear();
            staged.clear(); staged.putAll(current);stagedWorkspaceVersion=workspace.snapshot().version();
        }
    }

    private boolean sourceFilesChanged() {
        boolean changed = false;
        for (var entry : observedSources.entrySet()) {
            try {
                long stamp = Files.getLastModifiedTime(entry.getKey()).toMillis();
                if (stamp != entry.getValue()) {
                    changed = true;
                    entry.setValue(stamp);
                }
            } catch (IOException e) {
                changed = true;
            }
        }
        if (changed) {
            parses.clear();
            locations.clear();
            symbols.clear();
            typeBindings.clear();
        }
        return changed;
    }

    private static Prefix prefix(String source, int offset) {
        int start = offset, end = offset;
        while (start > 0 && Character.isJavaIdentifierPart(source.charAt(start - 1))) start--;
        while (end < source.length() && Character.isJavaIdentifierPart(source.charAt(end))) end++;
        int previous = start - 1;
        while (previous >= 0 && Character.isWhitespace(source.charAt(previous))) previous--;
        return new Prefix(
                start,
                end,
                source.substring(start, offset),
                previous >= 0 && source.charAt(previous) == '.');
    }

    private static SimpleName nameAt(CompilationUnit unit, int offset) {
        SimpleName[] found = {null};
        unit.accept(
                new ASTVisitor() {
                    @Override
                    public boolean visit(SimpleName node) {
                        int start = node.getStartPosition();
                        if (start <= offset && offset < start + node.getLength()) found[0] = node;
                        return true;
                    }
                });
        return found[0];
    }

    private static IBinding selectedBinding(SimpleName name) {
        ASTNode parent = name.getParent();
        for (ASTNode enclosing = parent;
                enclosing instanceof Name || enclosing instanceof Type;
                enclosing = enclosing.getParent()) {
            if (enclosing.getParent() instanceof ClassInstanceCreation creation) {
                Type target = creation.getType();
                while (target instanceof ParameterizedType parameterized)
                    target = parameterized.getType();
                if (name.getStartPosition() + name.getLength()
                        == target.getStartPosition() + target.getLength())
                    return creation.resolveConstructorBinding();
            }
        }
        if (parent instanceof ClassInstanceCreation creation
                && creation.getType().getStartPosition() <= name.getStartPosition())
            return creation.resolveConstructorBinding();
        if (parent instanceof SimpleType type
                && type.getParent() instanceof ClassInstanceCreation creation)
            return creation.resolveConstructorBinding();
        if (parent instanceof MethodInvocation method && method.getName() == name)
            return method.resolveMethodBinding();
        if (parent instanceof SuperMethodInvocation method && method.getName() == name)
            return method.resolveMethodBinding();
        return name.resolveBinding();
    }

    private static IBinding declarationBinding(IBinding binding) {
        if (binding instanceof IMethodBinding method) return method.getMethodDeclaration();
        if (binding instanceof IVariableBinding variable) return variable.getVariableDeclaration();
        if (binding instanceof ITypeBinding type) return type.getTypeDeclaration();
        return binding;
    }

    private static ITypeBinding receiver(SimpleName marker) {
        ASTNode parent = marker.getParent();
        if (parent instanceof MethodInvocation method && method.getName() == marker)
            return method.getExpression() == null
                    ? enclosingType(marker)
                    : method.getExpression().resolveTypeBinding();
        if (parent instanceof QualifiedName name) return name.getQualifier().resolveTypeBinding();
        if (parent instanceof FieldAccess field) return field.getExpression().resolveTypeBinding();
        if (parent instanceof SuperMethodInvocation) {
            ITypeBinding type = enclosingType(marker);
            return type == null ? null : type.getSuperclass();
        }
        return null;
    }

    private static boolean receiverIsType(SimpleName marker) {
        if (marker.getParent() instanceof MethodInvocation method
                && method.getExpression() instanceof Name name)
            return name.resolveBinding() instanceof ITypeBinding;
        if (marker.getParent() instanceof QualifiedName name)
            return name.getQualifier().resolveBinding() instanceof ITypeBinding;
        return false;
    }

    private static ITypeBinding enclosingType(ASTNode node) {
        for (ASTNode current = node; current != null; current = current.getParent()) {
            if (current instanceof AbstractTypeDeclaration type) return type.resolveBinding();
            if (current instanceof AnonymousClassDeclaration anonymous)
                return anonymous.resolveBinding();
        }
        return null;
    }

    private static boolean inStaticMethod(ASTNode node) {
        for (ASTNode current = node; current != null; current = current.getParent())
            if (current instanceof MethodDeclaration method)
                return Modifier.isStatic(method.getModifiers());
        return false;
    }

    private static void addMembers(
            ITypeBinding type,
            ITypeBinding from,
            boolean staticOnly,
            List<Candidate> candidates,
            Set<String> seen) {
        if (type == null || type.isRecovered() || !seen.add(type.getKey())) return;
        for (IMethodBinding method : type.getDeclaredMethods()) {
            if (method.isConstructor()
                    || !visible(method, from)
                    || staticOnly && !Modifier.isStatic(method.getModifiers())) continue;
            String insertion = method.getName() + "()";
            boolean zero = method.getParameterTypes().length == 0;
            // Parameter names are never invented or inserted as argument expressions.
            candidates.add(
                    new Candidate(
                            method,
                            insertion,
                            insertion.length() - (zero ? 0 : 1),
                            zero ? 10 : 30));
        }
        for (IVariableBinding field : type.getDeclaredFields())
            if (visible(field, from) && (!staticOnly || Modifier.isStatic(field.getModifiers())))
                candidates.add(new Candidate(field, field.getName(), field.getName().length(), 20));
        for (ITypeBinding nested : type.getDeclaredTypes())
            if (visible(nested, from))
                candidates.add(
                        new Candidate(nested, nested.getName(), nested.getName().length(), 25));
        addMembers(type.getSuperclass(), from, staticOnly, candidates, seen);
        for (ITypeBinding parent : type.getInterfaces())
            addMembers(parent, from, staticOnly, candidates, seen);
    }

    private static boolean visible(IBinding binding, ITypeBinding from) {
        int modifiers = binding.getModifiers();
        if (Modifier.isPublic(modifiers)) return true;
        ITypeBinding declaring = declaringType(binding);
        if (declaring == null || from == null) return false;
        if (topLevel(declaring).isEqualTo(topLevel(from))) return true;
        if (Modifier.isPrivate(modifiers)) return false;
        if (declaring.getPackage() != null
                && from.getPackage() != null
                && declaring.getPackage().isEqualTo(from.getPackage())) return true;
        return Modifier.isProtected(modifiers) && from.isSubTypeCompatible(declaring);
    }

    private static ITypeBinding topLevel(ITypeBinding type) {
        while (type.getDeclaringClass() != null) type = type.getDeclaringClass();
        return type;
    }

    private static void addLocals(
            CompilationUnit unit, ASTNode marker, int offset, List<Candidate> out) {
        Map<String, IVariableBinding> locals = new LinkedHashMap<>();
        unit.accept(
                new ASTVisitor() {
                    @Override
                    public boolean visit(SimpleName name) {
                        if (name.getStartPosition() >= offset || !name.isDeclaration()) return true;
                        if (!(name.resolveBinding() instanceof IVariableBinding variable)
                                || variable.isField()) return true;
                        ASTNode scope = variableScope(name);
                        if (scope != null && contains(scope, marker.getStartPosition()))
                            locals.put(variable.getName(), variable);
                        return true;
                    }
                });
        for (IVariableBinding variable : locals.values())
            out.add(new Candidate(variable, variable.getName(), variable.getName().length(), 0));
    }

    private static ASTNode variableScope(ASTNode node) {
        for (ASTNode parent = node.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof Pattern) {
                // Flow scopes are not lexical blocks. Conservatively support the positive direct
                // instanceof branch; other flow-sensitive patterns are withheld, never leaked.
                ASTNode condition = parent;
                while (condition.getParent() instanceof PatternInstanceofExpression
                        || condition.getParent() instanceof ParenthesizedExpression)
                    condition = condition.getParent();
                if (condition.getParent() instanceof IfStatement branch
                        && branch.getExpression() == condition) return branch.getThenStatement();
                return null;
            }
            if (parent instanceof Block
                    || parent instanceof MethodDeclaration
                    || parent instanceof LambdaExpression
                    || parent instanceof EnhancedForStatement
                    || parent instanceof ForStatement
                    || parent instanceof CatchClause) return parent;
        }
        return null;
    }

    private static String proposalIdentity(IBinding binding) {
        if (binding instanceof IMethodBinding method)
            return "method:"
                    + method.getName()
                    + "("
                    + String.join(
                            ",",
                            Arrays.stream(method.getParameterTypes())
                                    .map(type -> type.getErasure().getQualifiedName())
                                    .toList())
                    + ")";
        if (binding instanceof IVariableBinding) return "variable:" + binding.getName();
        if (binding instanceof ITypeBinding type) return "type:" + type.getQualifiedName();
        return binding.getKey();
    }

    private static void addTypes(CompilationUnit unit, List<Candidate> out) {
        unit.accept(
                new ASTVisitor() {
                    @Override
                    public boolean visit(SimpleName name) {
                        if (name.resolveBinding() instanceof ITypeBinding type
                                && !type.isRecovered())
                            out.add(
                                    new Candidate(
                                            type, type.getName(), type.getName().length(), 25));
                        return true;
                    }
                });
    }

    private List<Completion> typeCompletions(Context context, Prefix prefix) {
        if (prefix.value().length() < 2 || !Character.isUpperCase(prefix.value().charAt(0)))
            return List.of();
        CompilationUnit current = syntax(context.source());
        String currentPackage =
                current.getPackage() == null
                        ? ""
                        : current.getPackage().getName().getFullyQualifiedName();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (var entry : context.openSources().entrySet()) {
            CompilationUnit source = syntax(entry.getValue());
            String pkg =
                    source.getPackage() == null
                            ? ""
                            : source.getPackage().getName().getFullyQualifiedName() + ".";
            for (Object node : source.types())
                if (node instanceof AbstractTypeDeclaration type)
                    names.add(pkg + type.getName().getIdentifier());
        }
        for (Path root : context.sourceRoots()) {
            if (!Files.isDirectory(root)) continue;
            try (var paths =
                    Files.find(
                            root,
                            16,
                            (path, attrs) ->
                                    attrs.isRegularFile()
                                            && path.getFileName()
                                                    .toString()
                                                    .startsWith(prefix.value())
                                            && path.toString().endsWith(".java"))) {
                for (Path path : paths.limit(100).toList()) {
                    String relative = root.relativize(path).toString();
                    names.add(
                            relative.substring(0, relative.length() - 5)
                                    .replace(java.io.File.separatorChar, '.'));
                }
            } catch (IOException ignored) {
            }
        }
        for (Path binary : context.classpath()) {
            if (!Files.isRegularFile(binary) || !binary.toString().endsWith(".jar")) continue;
            try (ZipFile jar = new ZipFile(binary.toFile())) {
                jar.stream()
                        .map(java.util.zip.ZipEntry::getName)
                        .filter(
                                name ->
                                        name.endsWith(".class")
                                                && !name.contains("$")
                                                && !name.startsWith("META-INF/"))
                        .map(name -> name.substring(0, name.length() - 6).replace('/', '.'))
                        .filter(name -> simpleName(name).startsWith(prefix.value()))
                        .limit(100)
                        .forEach(names::add);
            } catch (IOException ignored) {
            }
        }
        if (platformTypes == null) platformTypes = indexPlatformTypes();
        platformTypes.stream()
                .filter(name -> simpleName(name).startsWith(prefix.value()))
                .forEach(names::add);
        List<String> ordered =
                names.stream()
                        .filter(name -> simpleName(name).startsWith(prefix.value()))
                        .sorted(
                                Comparator.<String>comparingInt(
                                                name ->
                                                        name.startsWith("java.lang.")
                                                                ? 0
                                                                : name.startsWith("java.util.")
                                                                        ? 1
                                                                        : 2)
                                        .thenComparing(String::compareTo))
                        .limit(32)
                        .toList();
        Map<String, Long> counts =
                ordered.stream()
                        .collect(
                                java.util.stream.Collectors.groupingBy(
                                        SharedJavaIntelligence::simpleName,
                                        java.util.stream.Collectors.counting()));
        List<Completion> result = new ArrayList<>();
        for (String qualified : ordered) {
            ITypeBinding type = resolveType(context, qualified, currentPackage);
            if (type == null || type.isRecovered()) continue;
            if (!Modifier.isPublic(type.getModifiers())
                    && !type.getPackage().getName().equals(currentPackage)) continue;
            String simple = simpleName(qualified),
                    pkg =
                            qualified.contains(".")
                                    ? qualified.substring(0, qualified.lastIndexOf('.'))
                                    : "";
            boolean imported = pkg.equals(currentPackage) || pkg.equals("java.lang");
            boolean conflict = false;
            for (Object object : current.imports()) {
                ImportDeclaration declaration = (ImportDeclaration) object;
                if (declaration.isStatic()) continue;
                String name = declaration.getName().getFullyQualifiedName();
                if (declaration.isOnDemand() && name.equals(pkg) || name.equals(qualified))
                    imported = true;
                if (!declaration.isOnDemand()
                        && simpleName(name).equals(simple)
                        && !name.equals(qualified)) conflict = true;
            }
            // Different same-named types are explicitly qualified; the engine never chooses an
            // import silently.
            boolean qualify = conflict || !imported && counts.getOrDefault(simple, 0L) > 1;
            String insertion = qualify ? qualified : simple;
            List<TextEdit> edits = List.of();
            if (!qualify && !imported) {
                String newline = context.source().contains("\r\n") ? "\r\n" : "\n";
                int position = 0;
                if (!current.imports().isEmpty()) {
                    ASTNode last = (ASTNode) current.imports().getLast();
                    position = last.getStartPosition() + last.getLength();
                } else if (current.getPackage() != null)
                    position =
                            current.getPackage().getStartPosition()
                                    + current.getPackage().getLength();
                String text =
                        position == 0
                                ? "import " + qualified + ";" + newline
                                : newline + "import " + qualified + ";";
                edits = List.of(new TextEdit(position, position, text));
            }
            result.add(
                    new Completion(
                            prefix.start(),
                            prefix.end(),
                            insertion,
                            insertion.length(),
                            signature(type),
                            qualified,
                            edits));
        }
        return result;
    }

    private ITypeBinding resolveType(Context context, String qualified, String currentPackage) {
        String key =
                context.file()+"|"+context.projectVersion()
                        + "|"
                        + context.classpath()
                        + "|"
                        + context.openSources().hashCode()
                        + "|"
                        + qualified;
        if (typeBindings.containsKey(key)) return typeBindings.get(key);
        String probe =
                (currentPackage.isEmpty() ? "" : "package " + currentPackage + ";\n")
                        + "class __TurboJTypeProbe { "
                        + qualified
                        + " __turbojType; }";
        Parsed parsed = parse(context, probe, "type:" + qualified);
        ITypeBinding[] binding = {null};
        parsed.unit()
                .accept(
                        new ASTVisitor() {
                            @Override
                            public boolean visit(VariableDeclarationFragment variable) {
                                if (variable.getName().getIdentifier().equals("__turbojType")
                                        && variable.resolveBinding() != null)
                                    binding[0] = variable.resolveBinding().getType();
                                return false;
                            }
                        });
        for (var problem : parsed.unit().getProblems()) if (problem.isError()) binding[0] = null;
        typeBindings.put(key, binding[0]);
        return binding[0];
    }

    private static String simpleName(String qualified) {
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }

    private static List<String> indexPlatformTypes() {
        Set<String> exported = new HashSet<>();
        ModuleFinder.ofSystem()
                .findAll()
                .forEach(
                        module ->
                                module.descriptor().exports().stream()
                                        .filter(export -> !export.isQualified())
                                        .forEach(export -> exported.add(export.source())));
        List<String> types = new ArrayList<>();
        try {
            Path modules = FileSystems.getFileSystem(URI.create("jrt:/")).getPath("/modules");
            try (var files = Files.walk(modules)) {
                files.filter(
                                path ->
                                        path.toString().endsWith(".class")
                                                && !path.getFileName().toString().contains("$"))
                        .forEach(
                                path -> {
                                    Path relative = modules.relativize(path);
                                    if (relative.getNameCount() < 3) return;
                                    String name =
                                            relative.subpath(1, relative.getNameCount()).toString();
                                    name = name.substring(0, name.length() - 6).replace('/', '.');
                                    int dot = name.lastIndexOf('.');
                                    if (dot > 0 && exported.contains(name.substring(0, dot)))
                                        types.add(name);
                                });
            }
        } catch (IOException | FileSystemNotFoundException ignored) {
        }
        return List.copyOf(types);
    }

    private static ITypeBinding declaringType(IBinding binding) {
        if (binding instanceof IMethodBinding method) return method.getDeclaringClass();
        if (binding instanceof IVariableBinding variable)
            return variable.getDeclaringClass() != null
                    ? variable.getDeclaringClass()
                    : variable.getDeclaringMethod() == null
                            ? null
                            : variable.getDeclaringMethod().getDeclaringClass();
        if (binding instanceof ITypeBinding type) return type;
        return null;
    }

    private static String owner(IBinding binding) {
        ITypeBinding type = declaringType(binding);
        return type == null ? "" : type.getQualifiedName();
    }

    private static String kind(IBinding binding) {
        if (binding instanceof IMethodBinding method)
            return method.isConstructor() ? "constructor" : "method";
        if (binding instanceof IVariableBinding variable)
            return variable.isField() ? "field" : "variable";
        if (binding instanceof ITypeBinding type)
            return type.isRecord()
                    ? "record"
                    : type.isInterface() ? "interface" : type.isEnum() ? "enum" : "class";
        return "symbol";
    }

    private static String signature(IBinding binding) {
        if (binding instanceof IMethodBinding method) return methodSignature(method, null);
        if (binding instanceof IVariableBinding variable)
            return modifiers(variable.getModifiers())
                    + typeName(variable.getType())
                    + " "
                    + variable.getName();
        if (binding instanceof ITypeBinding type)
            return modifiers(type.getModifiers()) + kind(type) + " " + type.getQualifiedName();
        return binding.getName();
    }

    private static String methodSignature(IMethodBinding method, MethodDeclaration source) {
        StringBuilder text = new StringBuilder(modifiers(method.getModifiers()));
        if (method.getTypeParameters().length > 0) {
            text.append('<');
            for (int i = 0; i < method.getTypeParameters().length; i++) {
                if (i > 0) text.append(", ");
                text.append(typeName(method.getTypeParameters()[i]));
            }
            text.append("> ");
        }
        if (!method.isConstructor()) text.append(typeName(method.getReturnType())).append(' ');
        text.append(method.getName()).append('(');
        String[] names = method.getParameterNames();
        ITypeBinding[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) text.append(", ");
            String type = typeName(types[i]);
            if (method.isVarargs() && i == types.length - 1 && type.endsWith("[]"))
                type = type.substring(0, type.length() - 2) + "...";
            text.append(type);
            if (source != null && i < source.parameters().size())
                text.append(' ')
                        .append(((SingleVariableDeclaration) source.parameters().get(i)).getName());
            else if (i < names.length) text.append(' ').append(names[i]);
        }
        text.append(')');
        if (method.getExceptionTypes().length > 0)
            text.append(" throws ")
                    .append(
                            String.join(
                                    ", ",
                                    Arrays.stream(method.getExceptionTypes())
                                            .map(SharedJavaIntelligence::typeName)
                                            .toList()));
        return text.toString();
    }

    private static String modifiers(int value) {
        String text = java.lang.reflect.Modifier.toString(value & 0x0d3f);
        return text.isBlank() ? "" : text + " ";
    }

    private static String typeName(ITypeBinding type) {
        return type == null ? "?" : type.getName();
    }

    private static boolean contains(ASTNode node, int offset) {
        return node.getStartPosition() <= offset
                && offset < node.getStartPosition() + node.getLength();
    }

    private String symbolCacheKey(Context context, String key) {
        return context.file()
                + "|"
                + context.version()
                + "|"
                + context.projectVersion()
                + "|"
                + context.source().hashCode()
                + "|"
                + context.openSources().hashCode()
                + "|"
                + key;
    }

    private Resolved resolved(Context context, Symbol symbol) {
        Resolved result = symbols.get(symbolCacheKey(context, symbol.key()));
        if (result == null) {
            Symbol refreshed = resolveSymbol(context, symbol.start());
            if (refreshed != null && refreshed.key().equals(symbol.key()))
                result = symbols.get(symbolCacheKey(context, symbol.key()));
        }
        return result;
    }

    private Located locate(Context context, Symbol symbol) {
        String cacheKey = symbolCacheKey(context, symbol.key());
        if (!sourceFilesChanged() && locations.containsKey(cacheKey))
            return locations.get(cacheKey);
        Resolved resolved = resolved(context, symbol);
        if (resolved == null) return null;
        IBinding binding = resolved.binding();
        ASTNode node = declarationNode(resolved.parsed().unit(), binding, false);
        if (node != null)
            return remember(
                    cacheKey, context.file(), context.source(), node, false, "Project source");
        ITypeBinding type = declaringType(binding);
        if (type == null) return null;
        String relative = topLevel(type).getQualifiedName().replace('.', '/') + ".java";
        var javaElement=binding.getJavaElement();
        if(javaElement!=null&&javaElement.getAncestor(org.eclipse.jdt.core.IJavaElement.COMPILATION_UNIT) instanceof org.eclipse.jdt.core.ICompilationUnit sourceUnit){
            Path original=workspace.original(sourceUnit);
            // A legacy folder inventory can include the source used to build a dependency JAR
            // below an unrelated nested directory. Only explicit project boundaries, declared
            // package roots or open buffers identify writable workspace source here.
            boolean declaredSource=original!=null&&(!context.projects().isEmpty()
                    ||context.sourceRoots().stream().anyMatch(root->root.resolve(relative).normalize().equals(original))
                    ||context.openSources().containsKey(original)||context.file().equals(original));
            if(declaredSource&&workspace.snapshot().sources().stream().anyMatch(s->s.file().equals(original))){
                Located exact=locateProjectFile(context,original,workspace.snapshot().source(original).text(),binding,cacheKey);
                if(exact!=null)return exact;
            }
        }
        if(type.isFromSource()) {
        Path memoryFile = sourceIdentities.get(relative);
        if (memoryFile != null) {
            String source =
                    memoryFile.equals(context.file())
                            ? context.source()
                            : context.openSources().get(memoryFile);
            if (source != null) {
                Located memory = locateProjectFile(context, memoryFile, source, binding, cacheKey);
                if (memory != null) return memory;
            }
        }
        LinkedHashSet<Path> roots = new LinkedHashSet<>(context.sourceRoots());
        roots.add(context.file().getParent());
        for (Path root : roots) {
            Path file = root.resolve(relative).normalize();
            if (!file.startsWith(root.normalize()) || !Files.isRegularFile(file)) continue;
            try {
                observedSources.put(file, Files.getLastModifiedTime(file).toMillis());
                Located project =
                        locateProjectFile(
                                context,
                                file,
                                context.openSources().getOrDefault(file, Files.readString(file)),
                                binding,
                                cacheKey);
                if (project != null) return project;
            } catch (IOException ignored) {
                /* An unavailable attachment must not break editing. */
            }
        }
        }
        for (Path archive : archives(context)) {
            Located attached = locateArchive(archive, relative, binding, cacheKey);
            if (attached != null) return attached;
        }
        return null;
    }

    private Located locateProjectFile(
            Context context, Path file, String source, IBinding binding, String key) {
        Map<Path, String> opened = new HashMap<>(context.openSources());
        opened.put(context.file(), context.source());
        Context target =
                new Context(
                        file,
                        source,
                        context.version(),
                        opened,
                        context.sourceRoots(),
                        context.classpath(),
                        context.projectVersion(),
                        context.sourceArchives(),context.projects());
        Parsed parsed = parse(target, source, "declaration");
        ASTNode declaration = declarationNode(parsed.unit(), binding, false);
        if (declaration == null) return null;
        return remember(key, file, source, declaration, false, "Project source");
    }

    private Located locateArchive(Path archive, String relative, IBinding binding, String key) {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            List<String> entries = archiveEntries.get(archive);
            if (entries == null) {
                entries =
                        zip.stream()
                                .filter(e -> !e.isDirectory() && e.getName().endsWith(".java"))
                                .map(java.util.zip.ZipEntry::getName)
                                .toList();
                if (archiveEntries.size() >= 12) archiveEntries.clear();
                archiveEntries.put(archive, entries);
            }
            String found = null;
            for (String entry : entries)
                if (entry.equals(relative) || entry.endsWith("/" + relative)) {
                    found = entry;
                    break;
                }
            if (found == null) return null;
            var entry = zip.getEntry(found);
            if (entry == null || entry.getSize() > 4_194_304) return null;
            String source;
            try (var stream = zip.getInputStream(entry)) {
                byte[] bytes = stream.readNBytes(4_194_305);
                if (bytes.length > 4_194_304) return null;
                source = new String(bytes, StandardCharsets.UTF_8);
            }
            ASTNode declaration = declarationNode(syntax(source), binding, true);
            if (declaration == null) return null;
            Path destination =
                    scratch.resolve("attachments")
                            .resolve(Integer.toHexString(archive.toAbsolutePath().hashCode()))
                            .resolve(found)
                            .normalize();
            if (!destination.startsWith(scratch.resolve("attachments"))) return null;
            Files.createDirectories(destination.getParent());
            if (!Files.exists(destination))
                Files.writeString(destination, source, StandardCharsets.UTF_8);
            String origin =
                    archive.getFileName().toString().equals("src.zip")
                            ? "JDK source"
                            : "Attached source: " + archive.getFileName();
            return remember(key, destination, source, declaration, true, origin);
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    private List<Path> archives(Context context) {
        LinkedHashSet<Path> archives = new LinkedHashSet<>(context.sourceArchives());
        for (Path binary : context.classpath()) {
            String name = binary.getFileName().toString();
            if (name.endsWith(".jar"))
                archives.add(
                        binary.resolveSibling(
                                name.substring(0, name.length() - 4) + "-sources.jar"));
        }
        archives.add(Path.of(System.getProperty("java.home"), "lib", "src.zip"));
        return archives.stream().filter(Files::isRegularFile).toList();
    }

    private Located remember(
            String key, Path file, String source, ASTNode node, boolean readOnly, String origin) {
        SimpleName identifier = declarationName(node);
        int offset = identifier == null ? node.getStartPosition() : identifier.getStartPosition();
        int length = identifier == null ? 0 : identifier.getLength();
        Located located =
                new Located(
                        new Declaration(file, source, offset, length, readOnly, origin),
                        node,
                        source);
        locations.put(key, located);
        return located;
    }

    private static SimpleName declarationName(ASTNode node) {
        if (node instanceof MethodDeclaration method) return method.getName();
        if (node instanceof AbstractTypeDeclaration type) return type.getName();
        if (node instanceof VariableDeclaration variable) return variable.getName();
        if (node instanceof EnumConstantDeclaration constant) return constant.getName();
        if (node instanceof AnnotationTypeMemberDeclaration member) return member.getName();
        return node instanceof SimpleName name ? name : null;
    }

    private static ASTNode declarationNode(
            CompilationUnit unit, IBinding binding, boolean structural) {
        ASTNode direct = unit.findDeclaringNode(binding.getKey());
        if (direct != null) return direct;
        ASTNode[] found = {null};
        unit.accept(
                new ASTVisitor() {
                    @Override
                    public boolean visit(SimpleName name) {
                        if (!name.isDeclaration()) return true;
                        IBinding candidate = name.resolveBinding();
                        if (candidate != null
                                && binding.getKey()
                                        .equals(declarationBinding(candidate).getKey())) {
                            found[0] = name.getParent();
                        }
                        return true;
                    }
                });
        if (found[0] != null) return found[0];
        if (binding instanceof IMethodBinding method && method.isDefaultConstructor())
            return declarationNode(unit, method.getDeclaringClass(), structural);
        if (!structural) return null;
        // Source archives need no bindings: compare owner and erased parameter signatures, never a
        // name alone.
        List<ASTNode> matches = new ArrayList<>();
        unit.accept(
                new ASTVisitor() {
                    @Override
                    public void preVisit(ASTNode node) {
                        SimpleName name = declarationName(node);
                        if (name == null || !name.getIdentifier().equals(binding.getName())) return;
                        if (binding instanceof IMethodBinding method
                                && node instanceof MethodDeclaration declaration) {
                            if (method.isConstructor() != declaration.isConstructor()
                                    || !ownerMatches(declaration, method.getDeclaringClass()))
                                return;
                            if (declaration.parameters().size()
                                    != method.getParameterTypes().length) return;
                            for (int i = 0; i < declaration.parameters().size(); i++) {
                                SingleVariableDeclaration parameter =
                                        (SingleVariableDeclaration) declaration.parameters().get(i);
                                String sourceType =
                                        parameter.getType().toString()
                                                + "[]"
                                                        .repeat(
                                                                parameter.getExtraDimensions()
                                                                        + (parameter.isVarargs()
                                                                                ? 1
                                                                                : 0));
                                if (!eraseName(sourceType)
                                        .equals(eraseName(method.getParameterTypes()[i].getName())))
                                    return;
                            }
                            matches.add(node);
                        } else if (binding instanceof ITypeBinding type
                                && node instanceof AbstractTypeDeclaration declaration) {
                            if (ownerMatches(declaration, type.getDeclaringClass()))
                                matches.add(node);
                        } else if (binding instanceof IVariableBinding variable
                                && node instanceof VariableDeclarationFragment) {
                            if (ownerMatches(node, variable.getDeclaringClass())) matches.add(node);
                        } else if (binding instanceof IVariableBinding variable
                                && node instanceof EnumConstantDeclaration) {
                            if (ownerMatches(node, variable.getDeclaringClass())) matches.add(node);
                        }
                    }
                });
        if (matches.size() == 1) return matches.getFirst();
        if (binding instanceof IMethodBinding method && method.isDefaultConstructor())
            return declarationNode(unit, method.getDeclaringClass(), true);
        return null;
    }

    private static String eraseName(String type) {
        StringBuilder erased = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < type.length(); i++) {
            char c = type.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (depth == 0 && !Character.isWhitespace(c)) erased.append(c);
        }
        String result = erased.toString();
        int dot = result.lastIndexOf('.');
        return dot < 0 ? result : result.substring(dot + 1);
    }

    private static boolean ownerMatches(ASTNode node, ITypeBinding owner) {
        List<String> names = new ArrayList<>();
        for (ASTNode parent = node.getParent(); parent != null; parent = parent.getParent())
            if (parent instanceof AbstractTypeDeclaration declaration)
                names.addFirst(declaration.getName().getIdentifier());
        if (owner == null) return names.isEmpty();
        List<String> wanted = new ArrayList<>();
        for (ITypeBinding type = owner; type != null; type = type.getDeclaringClass())
            wanted.addFirst(type.getName());
        return names.equals(wanted);
    }

    private static Javadoc javadoc(ASTNode node) {
        for (ASTNode current = node; current != null; current = current.getParent()) {
            if (current instanceof BodyDeclaration declaration) return declaration.getJavadoc();
            if (current instanceof PackageDeclaration declaration) return declaration.getJavadoc();
            if (current instanceof Statement) return null;
        }
        return null;
    }

    private String inheritedDocumentation(
            Context context, IMethodBinding method, Set<String> visited) {
        if (!visited.add(method.getKey())) return "";
        List<IMethodBinding> parents = new ArrayList<>();
        ITypeBinding owner = method.getDeclaringClass();
        collectMethods(owner.getSuperclass(), parents, new HashSet<>());
        for (ITypeBinding parent : owner.getInterfaces())
            collectMethods(parent, parents, new HashSet<>());
        for (IMethodBinding parent : parents) {
            if (!method.overrides(parent)) continue;
            Symbol symbol =
                    new Symbol(
                            parent.getKey(),
                            parent.getName(),
                            owner(parent),
                            signature(parent),
                            "method",
                            0,
                            0);
            Parsed parsed = parse(context, context.source(), "source");
            symbols.put(
                    symbolCacheKey(context, symbol.key()),
                    new Resolved(context, parsed, parent, null));
            Located located = locate(context, symbol);
            if (located != null && javadoc(located.node()) != null) {
                Javadoc doc = javadoc(located.node());
                String text =
                        plainJavadoc(
                                located.source()
                                        .substring(
                                                doc.getStartPosition(),
                                                doc.getStartPosition() + doc.getLength()));
                if (!text.isBlank()) return text + "\n\nInherited from " + owner(parent) + ".";
            }
        }
        return "";
    }

    /**
     * A non-executable text subset: strips HTML elements/resources; keeps paragraphs, lists and
     * code.
     */
    public static String plainJavadoc(String comment) { return dev.turboj.refactorj.api.DocumentationText.plainJavadoc(comment); }

    private static void collectMethods(
            ITypeBinding type, List<IMethodBinding> out, Set<String> seen) {
        if (type == null || type.isRecovered() || !seen.add(type.getKey())) return;
        out.addAll(Arrays.asList(type.getDeclaredMethods()));
        collectMethods(type.getSuperclass(), out, seen);
        for (ITypeBinding parent : type.getInterfaces()) collectMethods(parent, out, seen);
    }

    private static ITypeBinding receiverType(ASTNode node) {
        if (node instanceof ClassInstanceCreation creation)
            return creation.getType().resolveBinding();
        return null;
    }

    private static ASTNode invocationAt(CompilationUnit unit, int open) {
        ASTNode[] result = {null};
        unit.accept(
                new ASTVisitor() {
                    @Override
                    public void preVisit(ASTNode node) {
                        if (!(node instanceof MethodInvocation
                                || node instanceof ClassInstanceCreation
                                || node instanceof SuperMethodInvocation)) return;
                        if (!contains(node, open)) return;
                        int nameEnd = node.getStartPosition();
                        if (node instanceof MethodInvocation method)
                            nameEnd =
                                    method.getName().getStartPosition()
                                            + method.getName().getLength();
                        if (node instanceof SuperMethodInvocation method)
                            nameEnd =
                                    method.getName().getStartPosition()
                                            + method.getName().getLength();
                        if (node instanceof ClassInstanceCreation creation)
                            nameEnd =
                                    creation.getType().getStartPosition()
                                            + creation.getType().getLength();
                        if (nameEnd <= open
                                && (result[0] == null || node.getLength() < result[0].getLength()))
                            result[0] = node;
                    }
                });
        return result[0];
    }

    private static int invocationOpen(String source, int offset) {
        String code = codeMask(source);
        Deque<Integer> opens = new ArrayDeque<>();
        for (int i = 0; i < offset; i++) {
            if (code.charAt(i) == '(') opens.push(i);
            else if (code.charAt(i) == ')' && !opens.isEmpty()) opens.pop();
        }
        while (!opens.isEmpty()) {
            int index = opens.pop(), before = index - 1;
            while (before >= 0 && Character.isWhitespace(code.charAt(before))) before--;
            if (before >= 0
                    && (Character.isJavaIdentifierPart(code.charAt(before))
                            || code.charAt(before) == '>')) return index;
        }
        return -1;
    }

    private static int argumentIndex(ASTNode invocation, String source, int start, int end) {
        List<?> arguments =
                invocation instanceof MethodInvocation method
                        ? method.arguments()
                        : invocation instanceof SuperMethodInvocation method
                                ? method.arguments()
                                : invocation instanceof ClassInstanceCreation constructor
                                        ? constructor.arguments()
                                        : List.of();
        for (int i = 0; i < arguments.size(); i++) {
            ASTNode argument = (ASTNode) arguments.get(i);
            if (end <= argument.getStartPosition() + argument.getLength()) return i;
        }
        if (!arguments.isEmpty()) {
            ASTNode last = (ASTNode) arguments.getLast();
            int tail = Math.min(end, last.getStartPosition() + last.getLength());
            return arguments.size()
                    - 1
                    + (codeMask(source).substring(tail, end).contains(",") ? 1 : 0);
        }
        String code = codeMask(source);
        int nested = 0, argument = 0;
        for (int i = start; i < end; i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '[' || c == '{') nested++;
            else if (c == ')' || c == ']' || c == '}') nested--;
            else if (c == ',' && nested == 0) argument++;
        }
        return argument;
    }

    private static boolean isCode(String source, int offset) {
        if (offset == 0) return true;
        String mask = codeMask(source);
        char c = source.charAt(offset - 1);
        return mask.charAt(offset - 1) == c && !(c == '"' || c == '\'');
    }

    /** Mask comments/literals, preserving UTF-16 positions for punctuation scanning. */
    private static String codeMask(String source) {
        char[] result = source.toCharArray();
        int state = 0; // 0 code, 1 line comment, 2 block comment, 3 string, 4 char, 5 text block
        boolean escape = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i), next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (state == 0) {
                if (c == '/' && (next == '/' || next == '*')) {
                    state = next == '/' ? 1 : 2;
                    result[i] = result[++i] = ' ';
                } else if (c == '"' && i + 2 < source.length() && source.startsWith("\"\"\"", i)) {
                    state = 5;
                    result[i] = result[++i] = result[++i] = ' ';
                } else if (c == '"' || c == '\'') {
                    state = c == '"' ? 3 : 4;
                    result[i] = ' ';
                    escape = false;
                }
            } else {
                result[i] = c == '\n' ? '\n' : ' ';
                if (state == 1 && c == '\n') state = 0;
                else if (state == 2 && c == '*' && next == '/') {
                    result[++i] = ' ';
                    state = 0;
                } else if (state == 5 && source.startsWith("\"\"\"", i)) {
                    result[++i] = result[++i] = ' ';
                    state = 0;
                } else if (state == 3 || state == 4) {
                    if (!escape && ((state == 3 && c == '"') || (state == 4 && c == '\'')))
                        state = 0;
                    if (c == '\\' && !escape) escape = true;
                    else escape = false;
                }
            }
        }
        return new String(result);
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Semantic service is closed");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        parses.clear();
        symbols.clear();
        locations.clear();
        staged.clear();
        archiveEntries.clear();
        try (var files = Files.walk(scratch)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        } catch (IOException ignored) {
            /* OS temporary directory is also cleaned independently. */
        }
    }
}
