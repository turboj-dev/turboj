# Native Output command-line finale fixture

This isolated fixture belongs to the separately recorded final chapter that adds
TurboJ's real `Esc`, `:`, `! command`, `Return` interaction. The earlier
`hero-story` fixtures and frozen capture remain unchanged.

The Maven project pins Spring Boot 4.1.1, Java 21 and Maven wrapper 3.9.11, with the
same dependency set as the verified historical project. `GET /hello` returns a
real HTTP 200 JSON object with `status: 200` and `message: "OK"`. Object member
order is not significant and is preserved exactly as recorded in the output.

The service binds only to 127.0.0.1:18765. The capture refuses an occupied port,
imports this project into a fresh isolated workspace, runs the explicit native
Maven goal `spring-boot:run`, verifies HTTP readiness and process ownership, then
enters curl through actual Swing keyboard events in TurboJ's Output command row.
It finally invokes native Stop Maven and verifies owned descendants and the
listener have exited. No browser request is made to localhost.
