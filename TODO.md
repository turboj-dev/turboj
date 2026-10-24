# TODO

- [ ] Rename the formatter project from `javafmt` to **FormatJ**, with directory/repository name `formatj`.
  - Check consumers before changing Java packages or Maven coordinates.
  - Preserve the existing `javafmt` CLI command for compatibility.
  - Update project branding, documentation and build references.

- [ ] Move the ecosystem tools into sibling directories alongside `turboj/`, with one independent project per directory:

  ```text
  Workspace/
  ├── turboj/
  ├── formatj/
  ├── refactorj/
  ├── commandj/
  ├── mavenj/
  └── turbotest/
  ```

  - Preserve each project's history and independent build/test/release workflow.
  - Update dependency coordinates, build scripts, CI, packaging and documentation as needed.
  - Keep TurboJ integration through versioned Java libraries in-process, with external protocol adapters optional.
