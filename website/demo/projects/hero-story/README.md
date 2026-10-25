# Native hero story fixtures

These independent fixtures are entered into an isolated native TurboJ workspace by
`website/demo/capture/HeroStoryCapture.java`; historical fixtures are unchanged.

1. `HelloWorld.java` prints `Hello TurboJ!`.
2. `Price.java` uses integer cents and `PriceTest.java` contains exactly two tests.
3. The capture invokes real RefactorJ Rename (`discount` → `discountedPrice`) and
   Extract Method (`applyDiscount`). Result sources are captured artifacts, not
   prewritten fixture replacements.
4. `/100` → `/200` deliberately fails only the discount test; correction restores
   both. Auto Test on Save and Auto Run on Save are demonstrated independently.
5. `spring-hello` pins Java 21, Spring Boot 4.1.1, Maven 3.9.11 (wrapper checksum
   pinned) and serves `GET /hello` on loopback port 18765. Explicit Maven
   `spring-boot:run` owns the persistent server. A real external curl process
   checks its response; the browser will never contact this endpoint.

The native app bundles JUnit Jupiter 5.13.4. The capture verifies and records its
actual implementation version. Dependencies and wrapper distribution are cached
before the representative run. No credential-bearing Maven settings are read: the
controlled home supplies offline public-cache aliases and the installed Java 21
toolchain. Only the capture-owned Maven execution is stopped.

Reproduction and measured evidence are described in the capture validation report.
