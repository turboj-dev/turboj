# Editable-buffer recovery and save-conflict qualification — 18 November 2026

Recovery now writes a schema-v2 per-instance session under `Settings.directory()/sessions/v2/`.
It captures every editable open buffer, active identity, text, primary selection/caret/scroll,
encoding/BOM, dirty state and original disk fingerprint. Text flattening, hashing and filesystem
work run outside the EDT. The first pending change starts a two-second checkpoint deadline;
continued typing does not reset it. Edits during a write coalesce into the next checkpoint.
Switching to a read-only attachment does not cancel snapshots of the other editable buffers.
Each active instance holds its own file lock. A second instance cannot claim an active editor's
session, and normal shutdown removes only its own recovery. Recovery of an abandoned session
is claimed under a lock; the previous manifest is discarded only after the receiving instance
has written a durable replacement. Cancel retains it for later. Legacy `sessions/buffer.java`
remains importable. Corrupt v2 manifests and legacy files retain their bytes, show a diagnostic, and do not prevent
other valid sessions from being offered. A late review caught the legacy branch aborting valid
v2 offers; `corrupt-legacy-control.txt` preserves the failed control. The corrected branch handles
that failure like a corrupt v2 manifest, with a regression covering valid-session recovery and
released ownership after the legacy file is repaired.

Recovery never writes recovered source to its old path automatically. Existing open-buffer
identity collisions open a recovered Untitled copy. Original disk fingerprints ensure that a
subsequent Save refuses files modified outside TurboJ. Restored clean buffers are marked dirty
when their old disk fingerprint no longer matches. Undo history and project trust are not
serialized; restore does not silently authorize execution. Read-only dependency views are not
recovery content. The manifest has explicit 256-buffer/128-MiB limits, with visible failures. UTF-8 JSON is streamed through a byte-limited writer into an owned temporary file, fsynced and published with a required atomic rename; a failed/oversized snapshot leaves the last good manifest intact. An unsupported atomic move fails visibly rather than weakening publication semantics.

Conditional Save rechecks both the resolved symbolic target and content fingerprint immediately
before replacement, after encoding, temporary writing/fsync and metadata copying. A detected
conflict preserves the external file and the editor buffer and reports the path and Save As
recovery route. Owner/group and ACLs exposed by Java's file-attribute providers are copied.
A later native control demonstrated that macOS exposes no Java ACL view and that replacement
lost its ACL. The correction uses native `/bin/cp -p` to seed the owned staging file before
truncating/writing, retaining native ACLs and xattrs and failing closed on copy or write errors.
Conditional Save also compares a bounded metadata fingerprint before staging and immediately
before replacement, including actual xattr values, so a concurrent ACL/xattr or permission
change is not treated as permission to overwrite merely because source bytes are identical.
See [native metadata correction and actual tests](../product-sequence-2026-11-18/native-metadata/README.md).
The final compare/rename gap cannot be eliminated portably against an
uncooperative writer, and no filesystem-wide compare-and-swap claim is made.

## Executed checks

- `./mvnw -q -pl app -Djava.awt.headless=true -Dtest=RecoveryAndSaveTest,FilesAndThemesTest test`: **15 PASS** (9 recovery/save + 6 files/themes).
  Includes two active instances, durable handoff, all-buffer paths/selection/dirty state,
  corruption and legacy import, external edits/new-file/symlink-retarget conflicts, failed
  temporary publication, and a real child JVM forcibly terminated to verify lock release and
  retained unsaved source. The final recovery suite includes a bounded-stream failure that preserves the earlier manifest and removes its owned temporary file.
- `./mvnw -q -pl refactorj/refactorj-transactions -Dtest=WorkspaceTransactionsTest test`:
  **21 PASS**. Reuses existing interruption/rollback/restart-conflict controls and adds a
  concurrent directory-to-symlink replacement and case-only rename qualification.
- Initial case-only control failed with generic `EXTERNAL_CHANGE`; preserved in
  `case-only-control.txt`. The journal now diagnoses aliased source/destination names with
  `FILESYSTEM_ALIAS` before source writes. Case-sensitive filesystems follow the tested normal
  move/undo path; this Mac's case-insensitive filesystem safely refuses this unsupported move.
- `RecoverySmoke`: native keyboard-confirmed restoration harness added to
  `scripts/verify-keyboard-effects.sh`. Execute against the final nominated JAR, sequentially
  with other GUI checks. It also checks a checkpoint during continuous typing and a dirty-buffer checkpoint while
  a read-only source attachment is active. No native result is claimed here before that execution.

## Remaining qualification limits

The current host does not provide a network-filesystem fixture or a second-user ownership
fixture. Native macOS same-path ACL/xattr retention now has separate executed evidence;
Java ACL provider behavior on other hosts is still unverified. The text/POSIX refactoring
journal refuses destructive deletion/rename of ACL/xattr-bearing files rather than pretending
it can restore that metadata (macOS's OS-managed provenance tag is excluded from that refusal).
The remaining REF-01 cases remain unverified,
not silently promoted to passing. The record contains the actual focused results in
`validation.json`; it is not a complete-reactor or packaged-application certification.

## Final installed-app result

[RecoverySmoke](../product-sequence-2026-11-18/native-final/RecoverySmoke.txt) passed on the installed JAR `36ae34e6…ea33a9` and bundled runtime, including keyboard restoration, continuous typing and the read-only attachment case. The remaining filesystem boundaries above are unchanged.
