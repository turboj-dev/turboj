# Product sequence — execution ledger (18 November 2026)

**Historical checkpoint, superseded by the [final qualification record](README.md).**
The pending statements below preserve the execution history; they are not the
current delivery status. Consult the final record and root TODO.md for remaining work.

Baseline dd75866. This is an execution ledger, not a claim that all nine groups passed.
The authoritative backlog remains root TODO.md. Personal files listed in the P0 manifest are preserved.

1. AI-13: implementation and focused controls PASS (20 controller tests, six real Swing-key assertions).
   Await final combined artifact and template/JDT native regression. See ai13/.
2. QFX-02/06: contextual fixes and providers implemented; semantic/native qualification in progress.
3. QFX-10: actual project bindings, recursive macros, safe imports/formatting implemented; transaction
   and native qualification in progress. Contexts remain gated by actual JDK/preview compatibility.
4. UI-07/08 REF-01: all-buffer per-instance recovery and final external-file recheck implemented;
   recovery/transaction tests in progress. Case-only alias rename refuses safely on insensitive FS.
5. Cherry feedback: implementation follows group4; no completeness claim yet.
6. Distribution: pinned CI workflow and build-input/JAR receipt guards implemented; four receipt tests
   PASS; actual final package/Finder runtime verification pending. No release deployed.
7. Maven/toolchain/formatter: assigned after Quick Fix qualification; JDK17 must be executed.
8. Performance: baseline dd75866 built in /private/tmp/turboj-baseline-dd75866 (package, tests skipped).
   Comparable native measurements will run sequentially after active builds finish. Not measured yet.
9. Website: assigned after templates; no deployment authorized by this work.

Current native GUI owner: root. Agents coordinate shared reactor compilation; browser checks may run
separately, but no other Java GUI harness may contend for focus. Build failures caused by interim
shared edits are kept as controls and never counted as passing execution.
