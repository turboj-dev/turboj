# OpenJDK test inputs

`Objects.java` and `Optional.java` are unmodified source entries from the installed
Amazon Corretto `25.0.4.1+10-LTS` distribution's `lib/src.zip`. Their archive paths,
byte lengths, SHA-256 hashes and source-archive hash are recorded in
`provenance.json`. The original copyright and GPLv2 with Classpath Exception
notices remain intact. `LICENSE` and `ADDITIONAL_LICENSE_INFO` are copied from
the same distribution's `legal/java.base/` directory.

These files remain under their upstream license. JavaFMT's MIT license does not
relicense them. They are corpus data only, never compiled into or copied into
the formatter engine. The expected JavaFMT layout is not inferred from their
original formatting. Tests inspect token/comment preservation and idempotence.

Upstream projects: [OpenJDK](https://github.com/openjdk/jdk) and
[Amazon Corretto 25](https://github.com/corretto/corretto-25). The pinned local
distribution and byte hashes, rather than an unverified branch tip, identify
these exact test inputs. Do not rewrite these originals when updating golden
output or running the formatter on JavaFMT's own source.
