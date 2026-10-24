# JavaFMT Standard Style 1

This specification defines a new canonical Java layout. Java has no universally
mandated formatting standard. Product version 0.1.0 targets non-preview Java 25.
The name is provisional; existing projects already use `javafmt` and this project
does not claim exclusive rights or completed trademark clearance.

## Foundations and original decisions

The [Sun conventions](https://www.oracle.com/java/technologies/cc-java-programming-language.html)
were last revised on 20 April 1999 and are explicitly archived. Their
[indentation guidance](https://www.oracle.com/java/technologies/javase/codeconventions-indentation.html)
uses four-space units and an 80-column objective. JavaFMT retains four spaces,
but adopts spaces-only indentation, a 100-column objective and structural
continuations of eight additional spaces. These last choices are JavaFMT policy.

The [OpenJDK style guide v6](https://cr.openjdk.org/~alundblad/styleguide/index-v6.html)
is a December 2015 **draft**, not a language standard. Existing OpenJDK sources
provide examples of conventional Java layout, not a formatting oracle. The
[OpenJDK contributor guide](https://openjdk.org/guide/) also places changes in
the context of the surrounding project. JavaFMT never reorders members or
performs the unused-import cleanup some style guides recommend.

[Google Java Style](https://google.github.io/styleguide/javaguide.html) provides
a useful contrasting choice: two-space blocks and a 100-column limit. JavaFMT
shares the width objective, but has its own policy and implementation.
[gofmt](https://pkg.go.dev/cmd/gofmt) motivates deterministic tool-owned layout.
Its optional rewrite/simplification operations are deliberately absent here.

## Normative rules

- Four spaces per structural block; no indentation tabs. Continuation lines use
  eight spaces beyond their statement's structural indentation, without aligning
  to identifier lengths.
- K&R braces. Empty blocks may remain `{}`. `else`, `catch` and `finally` follow
  the preceding closing brace on the same line where comments permit it.
- Existing braces, declarations and statement ordering are never changed.
- Adjacent methods/constructors and separate type declarations have one blank
  line between them. Field declarations are separate lines. A control construct
  following a run of local declarations is separated by one blank line.
- One space surrounds assignment, binary and lambda operators. Commas have a
  following space; control keywords have a space before `(`. Calls, indexing,
  generic angle brackets and method references use syntax-specific spacing.
- Target width is 100 UTF-16 columns, not a hard limit. Layout prefers safe
  argument/comma, binary-operator and method-chain boundaries. Identifiers,
  literals, comments, package declarations and imports are never split merely
  to meet the target. Wrapping is deterministic and does not copy original
  arbitrary line breaks.
- Imports are preserved in their original order, including static/module imports
  and attached comments. They are neither removed, introduced nor converted to
  wildcards. Sorting is deferred until its binding equivalence and comment
  attachment can be established independently.
- Comment tokens remain verbatim and in order. Trailing comments stay attached
  to their preceding source line. License/Javadoc contents and internal newlines
  are protected, including code examples and preformatted HTML. Only surrounding
  whitespace is formatted.
- String, character, numeric and text-block token spellings are protected
  verbatim, including escapes and whitespace inside text blocks. Unicode escapes
  are scanned according to Java rules. Unusual inter-token escape sequences are
  protected rather than guessed at.
- Full-file output ends in exactly one generated newline. The standalone default
  is LF; `PRESERVE` uses the first physical source newline for generated whitespace.
  Physical bytes inside comments/literals stay unchanged under either policy.
  Consequently mixed endings inside protected tokens can remain.
- Range formatting expands to a safe enclosing syntax unit and reports that
  range. It may reject temporarily invalid source. It does not silently repair it.

The parser validates before and after layout; tokens, comments and structural
syntax must agree. Unsafe or unsupported input yields failure, original source
and no edits. Canonical changes in future versions require explicit release
notes and style-version policy; patch releases must not quietly restyle projects.

## Name and implementation provenance

The existing [tokuhirom/javafmt](https://github.com/tokuhirom/javafmt) repository
confirms prior use of the command/product name. No source from that project,
Google Java Format or the Eclipse formatter is copied into this engine. JDT is
used as the lexical/syntax frontend. Benchmark competitors are separate tools.
