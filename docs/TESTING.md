# Testing

## Test kinds in this project

- Unit tests (JUnit 5).
- Integration tests with Testcontainers for PostgreSQL and the broker; marked, need Docker.
- Contention tests with many concurrent clients against one seeded auction.
- Crash/restart tests of the relay and sink reporting expected, missing and duplicate ids.

## Rules

- A test names the behaviour it protects; a failing test message says what was expected and what happened.
- Randomised tests print their seed on failure and accept it as an argument to replay.
- Tests that touch the network, the disk or a container are marked and can be selected or excluded by that mark.
- A test that measures time or throughput is a benchmark, not a test; benchmarks never gate a build.

## Continuous integration expectations

- Every job has `timeout-minutes`; every action is pinned to a full commit SHA.
- No `|| true`, `continue-on-error`, or retries that hide a failing test.
- Sanitizer and race-detector jobs are separate jobs with their own logs kept as artifacts.
- The test command that CI runs is the same one documented in `DEVELOPMENT.md`.


## Flaky-test policy

A test that fails without a code change is a defect in the test or the code,
never noise. Procedure:

1. Reproduce with the recorded seed or run configuration; three consecutive runs.
2. If it reproduces, fix the cause before anything else lands.
3. If it does not, mark the test with a quarantine label that keeps it running but non-blocking, open an issue with the failing output, and remove the label within the next change to that area.
4. A quarantined test never stays quarantined silently: the label is listed in this file while it exists.

Currently quarantined: none.

