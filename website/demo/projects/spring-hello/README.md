# Spring Hello — recorded TurboJ demo fixture

This small, loopback-only application belongs to the reproducible website session.
It contains no credentials and has no external service requirements. Spring Boot
4.1.1 is pinned; the project compiles for Java 21. Its sole endpoint is:

```text
GET http://127.0.0.1:18765/hello
Hello from TurboJ!
```

Copy this directory outside the TurboJ checkout before opening it. That prevents
the checkout's own ancestor `.mvn/maven.config` from changing this project's local
repository. Do not run multiple copies on port 18765 at the same time.

1. Open `pom.xml` or this folder in TurboJ. Wait for dependency resolution. Passive
   import does not execute the wrapper or application.
2. Use **Build → Run Maven Goal**, enter `spring-boot:run`, approve the project's
   trust prompt, and review/confirm the displayed Maven command.
3. In an **external terminal**, run:

   ```sh
   curl --silent --show-error --fail http://127.0.0.1:18765/hello
   ```

4. Use **Stop Maven** to end the server. This is an explicit real Maven build and
   application process, not TurboJ Native compilation or a terminal inside TurboJ.

The fixture can also be run independently with `./mvnw spring-boot:run` and a
Java 21+ JDK. The official Apache Maven Wrapper 3.3.4 script downloads Maven 3.9.11
when needed; its distribution is pinned by SHA-256. A first run can need network
access to Maven Central. The recorded session uses already downloaded artifacts.

## Verified limitation in TurboJ 1.0.10

The unchanged native Maven importer and compiler resolve and compile this source.
However, both Fast Run and Isolated Run complete when `main` returns. A normal
`SpringApplication.run(...)` returns while server threads remain alive; the
resident worker then retires its JVM. Control runs observed Tomcat start followed
by a closed endpoint. The demo therefore uses the existing explicit Maven goal
instead of claiming persistent Spring service support in Fast Run. No keep-alive
hack or IDE modification was added.

`spring.main.log-startup-info=false` intentionally suppresses Spring's startup
identity line, which normally contains the local account and directory. The real
Tomcat startup and request logs remain enabled. This is a demonstration setup,
not a framework benchmark or a promise of arbitrary Maven plugin parity.

## Third-party provenance

`mvnw` and `mvnw.cmd` retain their Apache Software Foundation license headers.
The full Apache License 2.0 is included in `MAVEN-WRAPPER-LICENSE`.
They are the Maven Wrapper 3.3.4 scripts already present in the TurboJ repository;
the POSIX script's TurboJ-specific local-cache override has been removed so this
standalone fixture uses normal Maven Wrapper cache semantics.

The Maven 3.9.11 binary ZIP was checked against the official SHA-512 at
<https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip.sha512>.
The verified ZIP's SHA-256 is recorded in `.mvn/wrapper/maven-wrapper.properties`.

Primary references: [Spring requirements](https://docs.spring.io/spring-boot/system-requirements.html),
[Spring Maven run goal](https://docs.spring.io/spring-boot/maven-plugin/run.html),
[Apache Maven Wrapper](https://maven.apache.org/tools/wrapper/).
