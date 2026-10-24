# Compatibility and safety boundaries

javafmt 0.1.0 / JavaFMT Style 1 runs on JDK 25. The pinned syntax frontend is
Eclipse JDT Core/ECJ 3.44.0, configured for non-preview Java 25. No Java grammar
or third-party formatter implementation is copied into the engine.

The original corpus covers classes, interfaces, enums, records, sealed types,
generic nesting and shift operators, annotations, lambdas, method references,
switch expressions/patterns, anonymous/nested classes, try-with-resources,
multicatch, enhanced loops, varargs, diamond, `var`, modules, package metadata,
compact source files, module imports and flexible constructor bodies.
Preview primitive patterns are explicitly rejected. Parser recovery is disabled.

The parser's public scanner has a Java 25 module-import token limitation; the
adapter handles that specific token context, then requires a valid AST. Other
unknown scanner failures are errors. There is no speculative recovery path.

## Deliberate limits

- Full files with syntax errors are unchanged. Range formatting currently also
  requires a valid enclosing compilation unit; incomplete-editor regional parsing
  remains future work.
- Range edits expand to an enclosing syntax unit and surrounding whitespace.
  Full parsing and output verification still occur; this is not incremental parsing.
- The width target is soft. Protected comments, literals and long identifiers
  remain intact. Javadoc prose/internal indentation is not reflowed.
- Imports retain original order. Binding-aware import organization is absent.
- LF/PRESERVE changes generated whitespace only. CRLF inside protected comment
  or literal tokens remains unchanged even with LF output policy.
- A 4 Mi UTF-16 source limit bounds requests. Excessive nesting and parser/layout
  failures return original source with an explicit diagnostic.
- No preview syntax, semantic refactorings, formatter directives, style options,
  Maven/Gradle plugins, GraalVM distribution or formally verified semantics.
- Formatting has been tested on macOS arm64. Other host distributions require
  their own build and validation; they are not implied by Java portability.

The retained raw token order (including comments/literals), a successful reparse
and structural AST comparison are runtime safeguards. Generated tests and actual
compile/run comparisons exercise representative programs. They do not establish
correctness for every Java source. Report minimized failures with the original
source and exact formatter version.

## Version pinning

Pin the product version, not just Style 1. Changes that affect output require a
changelog entry and deliberate version decision. This 0.1.0 development artifact
is not a claim of a published stable release or exclusive ownership of the name.
