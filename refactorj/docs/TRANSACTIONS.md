# Workspace transactions

`refactorj-transactions` is a headless Java API. `ChangeSet.prepare(plan)` validates and previews without touching source or disk. Edits use half-open UTF-16 offsets and must be sorted, nonoverlapping and outside surrogate pairs. File operations are resolved against the original snapshot, including moves with edits and two-file swaps. Destination collisions and paths outside the workspace are rejected.

`WorkspaceTransactions` applies one logical operation through `WorkspaceStore`. Workspace revision, every source revision and content, source roots and classpath must still match. An editor implements the store to publish all buffer changes as one undo transaction. `InMemoryWorkspace` is the reference adapter for unsaved buffers. No Swing or Eclipse dependency is required. Undo and redo preserve unrelated edits but reject changes to any affected source. Revisions remain monotonic; history follows stack order.

`DiskWorkspace` handles closed files only. It checks UTF-8 source content, file identity and POSIX permissions, rejects symbolic source paths, and serializes cooperating writers with a file lock. Before replacing files it fsyncs a journal containing before/after images. Each replacement uses an atomic rename in the same directory. A multi-file operation is **not filesystem-wide atomic**. If a write fails, all known images are restored. If another writer changed a target, recovery stops and retains the journal instead of overwriting that content.

Same-path replacements preserve ownership, permissions and supported extended metadata. On macOS,
`FileMetadata` seeds the owned staging file with the fixed native `/bin/cp -p` executable before
writing new bytes, because Java's `AclFileAttributeView` is unavailable there. Native ACLs and
user extended attributes survive edit, rollback and restart-based Undo/Redo. Copy failures and
denied writes abort before replacement. No shell command interpolation is used.
A bounded metadata fingerprint records owner/group, permissions, ordered ACL entries and
sorted attribute names with their actual values before staging. It is compared immediately
before replacement, so concurrent chmod/xattr changes with identical source bytes also refuse
the edit. Unknown attribute names, unreadable metadata and excessive metadata fail closed;
native attribute inspection accepts at most 256 attributes and 1 MiB of total values. A final
check-to-rename race still exists; this is not an atomic filesystem compare-and-swap.

The current text/POSIX journal cannot recreate ACLs or user extended attributes after a file is
deleted. Delete and rename operations on such files therefore fail closed, both during preparation
and immediately before the actual deletion. macOS's automatically generated, protected
`com.apple.provenance` tag is excluded from this refusal; other attributes, including quarantine
and resource forks, are not excluded. Same-path edits retain provenance through native copying.
This is an explicit unsupported destructive-operation case, not complete metadata-aware rename
support. Case-only renames on an aliased filesystem are also refused before mutation.

Opening a workspace or preparing a preview never silently recovers interrupted writes. An incomplete journal yields `RECOVERY_REQUIRED`. Explicitly invoke `DiskWorkspace.openRecovering(...)` or `recover()` after reviewing the interrupted operation. Committed journals retain undo/redo across process restarts. Journals contain source text and use owner-only permissions on POSIX systems; they are local project data, not telemetry. Keep them out of version control and apply the same backup/access policy as source files. Automatic journal expiry is deliberately absent because it would silently remove undo history.

Boundaries: source files are limited to 16 MiB, journals to 64 MiB and workspaces/history to 10,000 entries. Recovery refuses malformed records and escaping paths. Disk commits cannot implement compare-and-swap against arbitrary noncooperating filesystem writers; a narrow final check/rename race remains for source and metadata. Network filesystems, power-loss durability of directory metadata, cross-user ownership and hostile concurrent directory replacement require additional platform qualification. The editor integration must use a buffer-aware store rather than DiskWorkspace for open documents.

Verification: `./mvnw -f refactorj/pom.xml -pl refactorj-transactions -am test`. The transaction suite covers 21 tests including 300 deterministic generated sparse edit cases, Unicode, stale plans, unsaved buffers, file swaps, collisions, cancellation during commit, injected mid-commit failures, abrupt-process simulation, recovery conflicts, malformed journals and persisted undo/redo. Five additional macOS-gated `NativeMetadataTest` cases execute native ACL/xattr operations and verify preservation, interrupted rollback, destructive refusal, metadata added immediately before deletion, and metadata changed during replacement staging. See the [native metadata evidence](../../docs/evidence/product-sequence-2026-11-18/native-metadata/README.md).
