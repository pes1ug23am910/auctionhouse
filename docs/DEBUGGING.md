# Debugging

## Method

1. Capture the failing command, its full output and the commit; do not rely on memory.
2. Reduce to the smallest input or test that still fails; record the seed if the run is randomised.
3. Form one hypothesis, add one assertion or log line that would confirm or refute it, run again.
4. Fix the cause, not the symptom; add the regression test in the same change.
5. Record the root cause in the change description so it can be found later.

## Logging

- Structured, one event per line, with a level and a component name.
- Log at the boundaries: requests in and out, files opened and synced, state transitions.
- Never log secrets, tokens or full request bodies from external services.

## Java specifics

- Remote debug: `JAVA_TOOL_OPTIONS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"`.
- `jcmd <pid> Thread.print` for a thread dump; `jcmd <pid> JFR.start duration=60s filename=rec.jfr` for a flight recording.
- SQL logging: set the JDBC logger to DEBUG for the local profile only; never in CI output.
- PostgreSQL: `EXPLAIN (ANALYZE, BUFFERS)` for plans; `SELECT * FROM pg_stat_activity` and `pg_locks` for lock waits; the server log shows deadlock detection with both statements.
- Testcontainers: attach a log consumer to the container to capture its stdout on failure; containers are reused within a JVM run.
- Redpanda: `rpk topic list`, `rpk topic consume <topic>`, `rpk group describe <group>` for offsets and lag.
- memcached: `echo stats | nc localhost 11211` for hit/miss counts; `echo 'flush_all' | nc localhost 11211` resets between tests.

## Project-specific checks

- Reproduce with the exact command from `DEVELOPMENT.md` before changing anything.
- Check the invariants in `ARCHITECTURE.md` first; most defects violate one of them.
