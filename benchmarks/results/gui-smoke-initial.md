# Initial desktop smoke observation

These measurements were captured from the first desktop smoke process, before the
later keyboard, save-race and REPL checks were added. They are retained alongside
subsequent runs rather than selecting the fastest result.

```text
Harness start to visible window: 842.27 ms (not native launcher timing)
1000-line file, compiler active, 120 synthetic keys: 120 paints
handler/edit→paint p50 0.481 / p95 1.265 / p99 1.610 ms
```

The measurement excludes OS input queuing, compositor work and display scanout.
It includes cold application initialization but does not include native launcher
startup. The same process passed Hello World, changed output, theme switching,
compiler errors, an infinite loop and Stop, console input, isolated execution and
stale-result rejection. Later runs add acceptance coverage and remain separate.
