# Stable app and DMG identity

The package receipt must remain outside the signed application. A receipt containing the launcher's hash cannot be added to that launcher's own resource seal: a subsequent signing pass changes the signature bytes and invalidates the earlier hash. Actual DMG installation qualification exposed this when `jpackage --type dmg` re-signed its staging copy after the receipt had been inserted.

The corrected local packaging sequence is:

1. Create the app image and copy the exact qualified build receipt into Resources.
2. Finalize ad-hoc signing once, preserving jpackage's entitlements/flags; verify with `codesign --verify --deep --strict`.
3. Write `TurboJ.package-receipt.json` beside the app, never inside it. Schema 2 records every regular file hash, symlink target and file permission mode, plus the effective build/packaging/bundled JDK identities. It rejects external bundle symlinks and binds the embedded build receipt and JAR.
4. Copy the sealed app with `ditto --rsrc --extattr` into an owned image staging directory, alongside the receipt and an Applications symlink. Verify signature and exact receipt identity again.
5. Create the compressed HFS+ DMG using `hdiutil create`; no second jpackage signing pass runs. The original app is verified again against the same external receipt.

A copied/installed app can be checked without its source checkout using:

```
python3 scripts/build-receipt.py verify-package --package /path/to/TurboJ.app --package-receipt /path/to/TurboJ.package-receipt.json
codesign --verify --deep --strict /path/to/TurboJ.app
```

Executed at this correction: **10 Python receipt tests passed**; shell syntax validation passed. Tests cover source/JAR binding, JAVA_HOME identity, external receipt placement without bundle mutation, copied bundle identity, re-signed launcher mismatch, added/removed files, changed modes/links, escaping symlinks and rejection of the obsolete package-receipt schema. Fixtures are controlled files; these unit checks do not claim actual signed DMG installation succeeded. The parent release qualification owns the final real app/DMG/installed identity check and launch evidence.

This remains a local ad-hoc signed development package. It is not Developer ID signed, notarized or deployed. No product Java source or JAR changed as part of this packaging correction.
