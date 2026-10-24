# Spring feasibility controls

These are **headless control runs**, not the complete session shown by the hero.
Their timings must not be substituted for the final session's timings.

The harness `../../capture/SpringControl.java` links to the unmodified packaged
TurboJ 1.0.10 JAR. It uses the same native Maven importer, toolchain selection,
compiler, worker and explicit lifecycle implementation as the editor.

- `standard-main.txt`: dependencies resolve and native compilation succeeds.
  Fast Run logs Tomcat startup, then completes when `main` returns. The worker
  reports residual threads and is retired. Subsequent curl exits 7.
- `isolated-main.txt`: reproduces the same endpoint shutdown with Isolated Run.
- `maven-main.txt`: the existing explicit Maven `spring-boot:run` lifecycle keeps
  the endpoint running. Real curl returns `Hello from TurboJ!` and exits 0.
  Cancellation terminates Maven and the server.

The initial sandbox-denied repository-lock attempt and an instrumentation-only
null-JDK error were corrected before these retained controls; neither is a
product performance measurement. The harness now uses the same `Toolchains.select`
step and fallback runtime as TurboJ.

The logs retain authentic messages and measurements. Only local account and
filesystem identities were replaced with bracketed labels. `index.json` records
the SHA-256 of the original local log and redaction categories. These are text
evidence, not edited screenshots. The final GUI capture instead suppresses the
Spring identity log at its source using a documented application setting, so it
can preserve screenshot pixels unchanged.

The local Maven repository already contained Spring Boot 4.1.1. Controls can warm
remaining Maven metadata, plugin and wrapper artifacts; the chosen replay must
declare those artifacts warm. Both importer cache status and total import
measurement are recorded separately by the final GUI capture.
