# Full application experiment

This separate Terraform root retains the thin root at `infra/`. It defines one
or two application EC2 hosts, one supporting EC2 host, shared private RDS and
an AWS Application Load Balancer. Thus one application host means two total
EC2 instances; two application hosts means three. ALB and RDS are additional
managed services. Changing application count within this state preserves the
shared database, dependency host and persistent dependency volume.

This module is local implementation until a separately authorized AWS run
establishes delivery, negative checks, recovery and teardown. It never creates
an OIDC provider, DNS registration or ACM certificate. Supply a validated
certificate in the selected Region and an owned hostname targeting ALB.
Application sessions and idempotency live in PostgreSQL; ALB stickiness is off.
Only ALB can reach app/gateway target ports. Broker/cache/telemetry listeners
accept the application security group only. Grafana is loopback-only through
an authenticated SSM port-forward. Bridge networking and IMDSv2 hop limit one
configure the host credential boundary; an actual AWS credential-access denial
check remains required.

The dependency host uses a separate encrypted EBS volume for Redpanda,
warehouse, metrics, traces and dashboards. It is a single failure domain;
replication-one Redpanda and single-AZ RDS do not provide high availability.
One shared memcached process is disposable. The experimental Node gateway is
packaged on every app host; Java SSE remains the ALB default unless explicitly
selected. Warehouse export/replay is a bounded operator-triggered job against
an immutable source cut, not an unimplemented continuous stream.

Use a distinct encrypted backend key ending in `/full/terraform.tfstate`,
record it in `state_backend_key`, and initialize using the private backend
configuration. The backend itself, GitHub identity provider and certificate
are externally owned and excluded from teardown. Do not reuse thin state.
All resource/expiry/cost inputs remain mandatory. Budget alerts are not caps.

See [the deployment guide](../../docs/DEPLOYMENT.md) and
`ops/cloud/full/README.md` for bootstrap, immutable release, measurement and
teardown. No plan/apply is authorized by the presence of these files.
