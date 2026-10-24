# Architecture

`javafmt` is an independent Maven project with its own parent, wrapper, version,
license and CLI distribution. It lives alongside TurboJ's modules in this
checkout for development, but builds from this directory alone. It has no
dependency on TurboJ. The enclosing reactor only aggregates it; TurboJ depends
on the resulting `dev.javafmt:javafmt-core:0.1.0` artifact.

The parser adapter uses supported JDT AST/scanner APIs without bindings or an
Eclipse workspace. Original raw token spans remain immutable. The layout engine
assigns whitespace documents to the gaps between tokens, using AST-derived
roles for blocks, declarations, type parameters, operators and statements.
A bounded deterministic line solver selects safe break opportunities; it never
searches exponentially or changes token text.

Each changed gap becomes a trimmed UTF-16 edit in original-source coordinates.
Edits are ordered and nonoverlapping. Validation reparses output, compares raw
tokens and AST structure, and rejects unsafe transformations. Public request
and result values are immutable. A formatter is safe for concurrent callers;
individual parse/layout state is confined to a call.

Range formatting reports its expanded syntax range and retains untouched gaps
outside it. It still parses the full source for context and validation. This is
range-limited editing, **not an incremental parser**; measured work and limitations
must not be obscured by the API name.

CLI formatting writes stdout or uses checked temporary-file replacement. A
bounded resident JSONL service supports external IDE clients without a JVM
launch per save. TurboJ calls the Java API directly on a background executor,
applies only results for the same document/version and groups edits in one undo
transaction. Neither engine nor policy knows about Swing, themes or an IDE.
