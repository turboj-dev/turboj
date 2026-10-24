# Spring chapter: selected session and measurement boundaries

The final GUI trace is the single complete `session-2026-10-24` session captured
on 2026-10-24. It follows the same Hello World, tests, refactoring and automation
chapters; its Spring timings are not taken from the faster headless controls.
The production IDE was not modified.

## Import

Opening the copied `spring-hello/pom.xml` through the real TurboJ frame took
**574.854 ms** from immediately before the frame's `openPath(pom.xml)` call to the settled model's final EDT
paint checkpoint. This interval includes project opening, scheduling, model and
dependency resolution, and the editor's pending configuration synchronization.
It is not a measurement of downloading 39 dependencies.

No model for this fresh project path existed at the start. By the final settled
publication the editor had performed a coalesced configuration refresh, so the
last MavenJ model reported **cacheHit=true**, and its own duration was
**8.187 ms**. That engine duration is part of the overall import sequence; it
must not be presented as the full user-visible import time.

The resulting model contains one Java 21 module and 39 dependency nodes. Its native
fast-path classification is `PARTIAL` because the Spring Boot Maven plugin
participates in the build. The actual interface explicitly says Maven
verification is required. Dependency artifacts and the Maven 3.9.11 wrapper
distribution were already downloaded during control runs.

## Server launch

The UI uses **Build → Run Maven Goal → spring-boot:run**, including the real
project-trust and command-review dialogs. The measured **2777.520 ms** starts
immediately before accepting the final command review and ends at an EDT paint
checkpoint after a background HTTP probe first receives status 200 and the exact
`Hello from TurboJ!\n` body. It includes real Maven startup and compilation, server
startup, availability observation and UI handling. It is not Spring's internal
startup duration and is not a TurboJ Native Run measurement.

The availability probe uses Java HttpClient, a 500 ms request timeout and 25 ms
between completed unsuccessful probes. The timing therefore measures first
observed readiness, with polling/scheduling limits; it does not prove the exact
instant at which Tomcat bound its port. Capture/checkpoint overhead is included.

## External curl

The subsequent actual command is run outside TurboJ:

```sh
curl --silent --show-error --fail http://127.0.0.1:18765/hello
```

Its measured process interval is **18.132 ms**, from immediately before
`ProcessBuilder.start` through stdout EOF and successful `waitFor` return. It
includes starting curl, the HTTP request/response and process completion.
The broader replay operation is **21.507 ms**, including capture bookkeeping and
the final EDT paint checkpoint. Both measurements belong to this exact request:

```text
Hello from TurboJ!
[exit 0]
```

The replay explicitly labels this as an external terminal. TurboJ has no terminal
emulator used by this session. **Stop Maven** terminated the demonstration server
afterwards; the trace records `springStopped=true`.

## Timeline and privacy

All intervals above use `System.nanoTime`. Wall timestamps are retained in the
source trace. Human/presentation pauses occur outside measured operations; the
selected replay makes **no timeline cuts**. No operation was accelerated or
assembled from a different run. Browser rendering remains frame-synchronized,
not millisecond-precise physical display timing.

The copied project is under a generic `/private/tmp/turboj-demo/…` workspace.
`spring.main.log-startup-info=false` suppresses Spring's account/directory identity
line before capture. Final screenshots were not edited to remove identities.
The separate earlier control text logs document their explicit path/account
redactions.

Native Fast Run/Isolated Run controls demonstrated that a normal Spring main
returns while server threads are still alive, after which the worker is retired.
The fixture retains a normal `SpringApplication.run` main and uses the already
implemented Maven action. No artificial blocking code or new IDE functionality
was introduced for the demo.
