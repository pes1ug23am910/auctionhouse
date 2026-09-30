# ADR 0006: Separate the full host experiment from the thin deployment

Status: accepted for implementation; actual AWS verification pending.

The host comparison needs a named load balancer and actual independent
application machines while keeping stateful dependencies constant. The thin
single-host application root cannot establish that comparison or package the
complete broker, cache, warehouse and observability paths.

Use a separate `infra/full` Terraform root and backend key with an AWS
Application Load Balancer, one or two application EC2 instances, one shared
dependency EC2 instance and private RDS. Keep the origin and shared resources
stable when changing application count. PostgreSQL owns sessions, authorization
and idempotency; ALB stickiness is disabled. Broker, cache and telemetry ports
accept only the application security group. Identity-provider registration,
owned DNS, ACM certificate and state storage remain explicit external inputs.

Package the Java application, optional Node gateway and batch warehouse as
immutable images. Pin dependency images separately. Deploy through fixed,
version-pinned SSM documents and compare actual running identities/settings
with the release manifest. Keep thin and full secret namespaces and state
separate. A failed application candidate restores the previous image pair;
schema or credential changes require their own compatibility review.

This topology has two or three total EC2 instances, not one or two total
machines. The single dependency host and single-AZ database remain failure
domains. A second application instance does not provide complete high
availability. Its additional database pool can increase shared-resource
pressure, and a one-instance rolling release has an availability gap.

The host harness checks real inventory, exact healthy ALB targets, immutable
artifacts and observed request counters, then reconciles durable outcomes and
source/sink events. Local contracts and container smokes verify tooling only.
Cloud TLS, identity/network negatives, reboot/rollback, cost/teardown and
repeated host measurements require a separately authorized real AWS run.

See [full deployment](../../infra/full/README.md),
[operator commands](../../ops/cloud/full/README.md) and
[host comparison](../../experiments/hosts/README.md).
