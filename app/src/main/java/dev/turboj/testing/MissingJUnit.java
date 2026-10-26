package dev.turboj.testing;

import dev.turboj.compiler.CompilerDiagnostic;
import java.util.*;
import java.util.regex.Pattern;

/** Recognises compiler errors that only adding JUnit can fix, so TurboJ can offer the fix instead of a bare failure. */
public final class MissingJUnit {
    private static final Pattern MISSING_PACKAGE = Pattern.compile("package org\\.junit(\\.[\\w.]+)? does not exist");
    private static final Pattern MISSING_CLASS = Pattern.compile("symbol:\\s+class (\\w+)");
    private static final Set<String> ANNOTATIONS = Set.of("Test", "BeforeEach", "AfterEach", "BeforeAll", "AfterAll",
            "DisplayName", "Disabled", "Nested", "Tag", "Timeout", "RepeatedTest", "TestFactory",
            "Before", "After", "BeforeClass", "AfterClass", "Ignore");
    private MissingJUnit() {}

    /** Offsets in source where Cmd+1 can add JUnit: unresolved test annotations first, then org.junit imports. */
    public static List<Integer> locations(List<CompilerDiagnostic> diagnostics, String source) {
        var annotations = new ArrayList<Integer>(); var imports = new ArrayList<Integer>();
        for (CompilerDiagnostic diagnostic : diagnostics) {
            if (!diagnostic.isError() || diagnostic.start() < 0 || diagnostic.start() >= source.length()) continue;
            int start = (int) diagnostic.start();
            var symbol = MISSING_CLASS.matcher(diagnostic.message());
            if (diagnostic.message().startsWith("cannot find symbol") && symbol.find() && ANNOTATIONS.contains(symbol.group(1))
                    && start > 0 && source.charAt(start - 1) == '@') annotations.add(start);
            else if (MISSING_PACKAGE.matcher(diagnostic.message()).find()) {
                int line = source.lastIndexOf('\n', start) + 1;
                // A static import (Assert.*) has no Jupiter equivalent that keeps its meaning; only annotations move.
                if (!source.startsWith("import static", line)) imports.add(start);
            }
        }
        annotations.addAll(imports);
        return annotations;
    }
}
