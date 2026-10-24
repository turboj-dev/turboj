# Contributing

Use Java 25 and `./mvnw verify`. Keep core independent from GUI/project infrastructure.
Add a regression test for each binding, threading or dispatch defect. Preserve disabled-command
reasons and reject ambiguous shortcuts. Verify upstream shortcut changes against primary sources.
Benchmark hot-path changes with JMH; never infer editor responsiveness from a lookup benchmark.
