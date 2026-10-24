# Build and release

1. Use JDK 25 and the pinned Maven wrapper. Install `javafmt-core:0.1.0` for the optional formatter adapter, then run `./mvnw -f refactorj/pom.xml verify`.
2. Generate API documentation with `./mvnw -f refactorj/pom.xml -pl refactorj-api javadoc:javadoc`.
3. Run `python3 refactorj/scripts/package.py`. It audits the exact shipped dependency hashes and legal resources. The ZIP contains independent CLI/LSP/MCP launchers and intact runtime bundles.
4. Follow the independent IntelliJ and VS Code READMEs to build and run host tests. Refresh their bundled engine distribution after any runtime change. Do not include test-only plugin startup classes in the release ZIP.
5. Run native TurboJ acceptance tests against its packaged JAR, then build its macOS distribution separately. TurboJ consumes ordinary versioned API artifacts, not protocol adapters.
6. Record exact test/host/benchmark results and limitations. Verify source attribution, dependency licenses, name usage, signing and publication credentials before any public upload. Local ZIP/VSIX construction does not publish a marketplace release.

Pin exact versions for reproducible consumption. The Maven dependency graph is versioned; archive checksums and dependency provenance are recorded by the packaging audit. ZIP builds include local timestamps and are not claimed byte-for-byte reproducible across machines. Signing/notarization and Maven Central/marketplace publication are separate authorized release actions.
