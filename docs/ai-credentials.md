# AI settings and credentials

**Options → AI Completion → Configure AI completion…** opens a small native dialog. AI starts
disabled. The Local tab configures an existing Ollama or llama.cpp service on a
loopback endpoint; the Cloud tab configures a Responses-compatible HTTPS endpoint,
model and personal API key. The Behavior & privacy tab configures the idle delay,
timeout, generated-token bound, context bound, file exclusions and optional local
diagnostics. The dialog does not download a model or launch an inference service.

Cloud source sharing requires an explicit checkbox and confirmation before
enabling it. The explanation identifies the endpoint and possible API charges.
Automatic cloud suggestions have a separate checkbox and default to off. Editing
the cloud endpoint or model clears source-sharing consent and automatic cloud
suggestions; the user must approve the new configuration. Switching to Disabled
or Local does not require cloud consent.

The API key field is always empty when the dialog opens, even when a key is
already present. A blank field means “keep the existing key.” A typed key is
stored only when Apply is selected. “Remove this endpoint's stored key” is also
applied explicitly, and requires Disabled or Local mode. Cancel does not save
preferences or alter stored credentials. Test connection is an explicit
availability request containing model/authentication metadata, never editor or
project source. A newly typed key can be tested without saving it.

## macOS Keychain implementation

`MacKeychainCredentialStore` uses the stable Java 25 Foreign Function & Memory
API to call the current Security.framework `SecItemCopyMatching`, `SecItemAdd`,
`SecItemUpdate` and `SecItemDelete` APIs. CoreFoundation dictionaries and data
objects are released after each operation. No subprocess, shell script, command
line argument, temporary credential file or plaintext fallback is used.

Production keys use the generic-password service `com.turboj.ai.credentials`.
The account is the normalized HTTPS endpoint: host case, default port and trailing
slashes normalize, while distinct hosts, ports and API paths remain separate.
Changing a cloud destination therefore does not silently reuse a credential
stored for a different endpoint. Endpoint user information, query parameters and
fragments are rejected. Only this service/account pair is queried or changed;
TurboJ does not enumerate the user's Keychain.

All Keychain and provider operations run on a background worker. Calls from the
Swing event thread are rejected. Keychain initialization is lazy, so starting
TurboJ with AI disabled does not load or access it. macOS may display its own
unlock/access prompt, especially after a packaged application's signing identity
changes. A denied or unavailable Keychain leaves AI unavailable without exposing
a plaintext fallback. Cloud credential storage is currently macOS-specific.

The bundled launcher already supplies `--enable-native-access=ALL-UNNAMED`.
Development Java launches should supply that option too. Secret character/byte
buffers controlled by the settings/store code are cleared after use. Java HTTP
headers still require an in-memory string; this is not a claim that every runtime
copy can be forcibly erased. Keys are never written to TurboJ configuration,
metrics, source files, logs or the website.

Apple documents the [SecItem API](https://developer.apple.com/documentation/security/secitemadd(_:_:))
and the distinction between [macOS Keychain implementations](https://developer.apple.com/documentation/technotes/tn3137-on-mac-keychains).
The calls were verified against the installed macOS SDK headers as well as a real
disposable Keychain item on this Mac.

## Verification

Five automated tests cover endpoint identity, rejection of unsafe destinations,
the EDT guard and renewed consent for cloud/model/automatic changes. They never
access a real Keychain. The explicit `KeychainSmoke` executable created, read,
updated and removed a random test-only entry under `dev.turboj.ai.test.<UUID>`.
It verified endpoint separation and missing-entry handling, then removed the
entry in `finally`. No existing credential was read or modified.

The same test also passed using the Java runtime and application JAR included in
`target/release-ai-1.0.5/TurboJ.app`. This verifies the packaged runtime's native
Keychain integration, including cleanup, without using a separately installed JDK
or reading a personal API credential.

The settings GUI smoke uses a loopback protocol fixture and a memory-only
credential store. It checks that opening the dialog never reads existing keys,
connection testing sends no source, credential operations occur off the EDT,
Local/Cloud Apply behavior, explicit consent, endpoint invalidation, Cancel and
the three primary themes. It captures screenshots under ignored
`app/target/ai-settings/`.

The GUI check passed on the available macOS ARM64/Java 25 environment. An EDT
round trip during the fixture's 300 ms provider delay took 3.45 ms in the latest
run (5.43 ms in the earlier run); these are local dispatch observations, not
input-to-paint or model latency benchmarks. The auxiliary GUI launch used
`-Dapple.awt.UIElement=true` and
`-Dapple.laf.useScreenMenuBar=false`; earlier launch attempts stalled in macOS
AppKit initialization before any settings code ran and were terminated.

After building test classes, the explicit Keychain test can be run with:

```sh
java --enable-native-access=ALL-UNNAMED \
  -cp app/target/test-classes:app/target/turboj-app.jar \
  dev.turboj.ai.KeychainSmoke
```

The GUI fixture requires a full development JDK with `jdk.httpserver`; it is not
a customer-facing application entry point.
