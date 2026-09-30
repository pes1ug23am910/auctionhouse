# 0005 - Compare actual Kafka using the existing delivery contract

**Status:** accepted

## Context

Redpanda's Kafka API is the default local transport. Its compatibility does
not establish behavior with an actual Apache Kafka broker. The same producer,
consumer, event identity, acknowledgement and crash-recovery contract can be
exercised without introducing another business implementation.

## Decision

Retain Redpanda as the default and provide a separately pinned Apache Kafka
4.3.1 JVM/KRaft profile. Run the same20 application-process crash cases and
explicit committed-event-set oracle against it. Record real broker settings,
failures and semantic differences before treating the comparison as verified.

Each local profile has one replica and a persistent volume. Kafka explicitly
sets its per-message flush policy; Redpanda disables write caching. Neither
setting or a process restart is evidence about physical power-loss behavior
or replicated high availability.

## Consequences

The broker choice remains a deployment setting. The comparison adds an
optional local container and verification window, not a second authoritative
store or alternate event schema. Test topics are disposable and uniquely
named. Existing business data and the other broker's volume are preserved.
No throughput ranking is inferred from correctness/failure checks.

## Alternatives considered

- Infer Kafka behavior from compatible APIs: insufficient product-specific evidence.
- Switch the default before measurement: no demonstrated operational benefit.
- Introduce a Kafka-specific business path: weakens the common contract being compared.
