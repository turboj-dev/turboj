# Native macOS source metadata — 18 November 2026

Status: **VERIFIED in isolated native filesystem tests and the final reactor**.
The [final 976-test reactor](../final-reactor/reactor-results.json) includes all 14
`RecoveryAndSaveTest`, five `NativeMetadataTest` and 21 `WorkspaceTransactionsTest`
cases, with zero failures, errors or skips. The resulting JAR
`36ae34e651d3a60a47ba988a85ab91dae61d6b0445fd556137293f0504ea33a9` has a separately
verified [DMG installation identity](../package/installation.json); the installed
application also passed [RecoverySmoke](../native-final/RecoverySmoke.txt).
The metadata tests above ran in the reactor, not as a separate repetition inside
the installed app. There is no remaining reactor/package gate for this record.

The initial control compiled current `SafeFiles` into a private temporary directory and saved an owned Java file with a real native ACL. `AclFileAttributeView` returned null. Save succeeded, but `ls -le` showed its ACL disappeared. The exact failing control is retained in `before-fix-native-acl.txt`.

The shared `FileMetadata` correction seeds an owned staging file through `/bin/cp -p` on macOS, using `ProcessBuilder` arguments, then truncates/writes/fsyncs the intended bytes and retains the existing final content/target conflict check. Native tool failures and denied writes fail closed; staged files are cleaned up. This preserves the source ACL and extended attributes without depending on Java exposing an ACL provider. The standard Java owner/group/permissions checks remain in place. Other providers copy exposed ACL/user attributes; those platforms are not newly certified here.

The final conditional Save guard captures a canonical metadata fingerprint before staging and compares it immediately before publication, after the controlled concurrency hook. It includes owner/group, permissions, ordered ACL entries and sorted xattr names with their actual byte values, including OS-managed provenance. Native output is captured with bounded memory and timeout; there are no unbounded temporary capture files. Unknown native attribute names, unreadable metadata, more than 256 attributes or more than 1 MiB of values are refused without replacing source. This catches metadata-only changes that the source fingerprint cannot detect.

DiskWorkspace uses the same preservation and metadata fingerprint check during same-path edits and rollback. Its journal stores source text and POSIX permissions, so delete/rename of files carrying ACLs or user extended attributes is explicitly refused, including a second check immediately before deletion after the fault/concurrency hook. The automatically generated protected macOS `com.apple.provenance` attribute alone does not forbid ordinary deletion/rename; it is OS-managed rather than user-restorable metadata. Quarantine, resource forks and other attributes are not exempt. This exception was necessary because the first all-attribute refusal broke ordinary Java-created-file moves on this host; the failed 33/36 control is retained.

Initial preservation run: **36/36 PASS** (`isolated-tests.txt`). Final stronger metadata-guard run: **40 tests, 40 PASS, 0 skipped** (`metadata-guard-tests.txt`):

- `RecoveryAndSaveTest`: 14, including native Save ACL/xattr retention, shorter replacement truncation, owner permissions, denied native write preservation and stage cleanup, plus real concurrent ACL/xattr-value/permission changes, unsupported attribute names and the separately fixed corrupt-legacy/valid-v2 recovery control.
- `NativeMetadataTest`: 5, including real edit → application restart → Undo/Redo, injected interrupted multi-file rollback, initial delete/rename refusal and metadata attached immediately before actual deletion and ACL/xattr changes after replacement staging.
- `WorkspaceTransactionsTest`: existing 21 including normal disk move/create/delete/restart and safe case-only rename refusal.

The tests used Corretto 25.0.4.1+10-LTS and JUnit 5.13.4 on this macOS arm64 host. Current changed production/tests were compiled with `javac` to `/private/tmp/turboj-native-metadata-tests-6xka8ssj`, ahead of the nominated candidate JAR on the classpath, then executed using the JUnit Platform 1.13.4 launcher. No shared Maven target, GUI session or personal source file was changed by this qualification. `metadata-guard-tests.txt` is the actual final output; production source hashes are recorded separately.

Boundaries: no claim of filesystem-wide atomicity, atomic compare-and-swap against uncooperative external writers, cross-user ownership qualification, network-filesystem behavior, or directory-metadata persistence after power loss. Full metadata-aware destructive rename/recovery remains unsupported and is refused rather than silently losing attributes.
