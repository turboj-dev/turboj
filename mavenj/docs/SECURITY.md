# MavenJ security boundaries

Opening a project is metadata inspection. The core never invokes Maven, a wrapper,
a shell, project plugins, build extensions or annotation processors. Processor
artifacts can be resolved as data; executing them is a separate trusted operation.
Real lifecycle execution must use the workspace trust policy and cancellable
process service. A read-only MCP inspection request does not authorize a build.

Repository access is denied unless its effective URL is explicitly allowed.
Mirrors, proxy/authentication settings, local-repository layout and offline mode
use Maven/Resolver APIs. External HTTP and credential-bearing repository URLs
are blocked. Loopback HTTP requires explicit permission. Redirects are disabled
to prevent a permitted endpoint from escaping its authorization boundary.
Certificates are verified normally; MavenJ does not install permissive TLS
handlers or upload project metadata to TurboJ servers.

The official Maven settings decrypter reads encrypted server credentials. Secrets
remain in memory for Resolver authentication and are scrubbed from diagnostics and
public serialized effective POMs. Sensitive process-property values are also
redacted; restricted inspection rejects direct interpolation of sensitive process
properties before model evaluation. Credential-like properties/XML fields are
redacted; system properties and environment variables are not indiscriminately
exported as public module properties. MavenJ does not copy decrypted credentials
into project files. Consumers should still avoid logging whole import requests,
which can contain caller-supplied secret user properties.

POM/settings DTDs are rejected before Maven XML parsing, including non-UTF-8 XML,
to prevent entity-based local-file/network access. Unsaved POM overlays stay in
memory. Reactor descriptor files are placed in private temporary directories and
removed after import. The importer does not create or modify project build
outputs. Relative parent POMs and configured source roots may legitimately be
outside an aggregator directory; the MCP adapter uses `DefaultMavenJ(allowedRoot)` to reject local module and
relative-parent POM reads outside the canonical root, including symlinks, before
reading their contents. Resolver-selected local-repository metadata remains
accessible. Ordinary embedded imports can use legitimate external reactor roots.

Cancellation is cooperative with transfer callbacks and bounded HTTP timeouts.
No filesystem-wide atomicity or cancellation of arbitrary third-party plugin code
is claimed. Actual Maven/plugin execution is isolated from passive import, and
its limitations belong to the lifecycle/native-compiler integration contracts.

Automated core tests exercise zero-request denial, authenticated mirrors,
offline reuse, on-demand attachments, secret redaction, DTD rejection, passive
processor discovery and cancellation. These are concrete tests, not a claim that
all possible Maven plugins or third-party repositories are safe.

Offline lifecycle preflight rejects a configured wrapper distribution that is not
present in supported wrapper cache layouts, and rejects a missing required wrapper
JAR. This prevents normal wrapper bootstrap from silently downloading in offline
mode. Trusted custom wrapper scripts remain executable project code; `--offline`
is a Maven policy, not an operating-system network sandbox.
