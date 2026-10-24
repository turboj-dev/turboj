# Changelog

## 0.1.0 — JavaFMT Style 1, development build

- Original AST-guided whitespace layout with raw-token and structural validation.
- Java 25 non-preview parsing, conservative comments/literals, soft 100-column wrapping.
- Versioned UTF-16 edits, range expansion, explicit failure with original source.
- Standalone CLI, atomic writes, check/diff/stdin and resident editor protocol.
- In-process TurboJ integration and independent external IDE adapters.
- Correctness corpus, generated tests and reproducible latency/allocation workloads.

This establishes the initial formatting output. It does not announce public
repository publication, trademark clearance or a stable compatibility guarantee.
