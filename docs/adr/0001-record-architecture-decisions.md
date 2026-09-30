# 0001 — Record architecture decisions

**Status:** accepted

## Context

Design choices in this project (on-disk formats, interfaces, concurrency
schemes, protocol subsets) need a durable, reviewable rationale that lives
with the code.

## Decision

Keep architecture decision records under `docs/adr/`, one numbered file per
decision, using the template in this directory.

## Consequences

Every non-obvious design choice gets a record before or with the change that
implements it. Superseded records stay in place and point forward.

## Alternatives considered

Rationale in commit messages only: too scattered to review. A single design
document: goes stale and mixes decisions of different ages.
