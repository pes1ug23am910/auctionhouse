# Full deployment and recovery

The independent root `infra/full` adds a complete application topology and the
one-versus-two application-host experiment. It retains the thin root. Local
checks validate implementation and packaging; no actual AWS run is implied.

The public AWS ALB serves only the configured HTTPS hostname with an existing
validated ACM certificate. Unknown Host values receive 403. Java uses native
Tomcat forwarding for ALB's protocol/port headers, a fixed canonical OIDC
callback and secure session cookies. No confidential-client secret is accepted:
this interface requires a public authorization-code/PKCE provider registration.
The actual public login and spoofed-header/network negatives remain cloud
acceptance checks.

One or two `t3.small` application hosts each run Java (512MiB heap, 1GiB
container, one CPU) and the experimental gateway (256MiB, half CPU). The default
ALB event route goes directly to Java. A separate `t3.large` supporting host
runs replication-one Redpanda, memcached, collector, Tempo, Prometheus and
Grafana; warehouse is a bounded on-demand job. A shared single-AZ RDS instance
stores JDBC sessions, authorization, bids, replay identities, outbox and sink.
ALB stickiness is disabled. Two application hosts therefore means **three EC2
hosts**, plus RDS and ALB. This is an experiment, not a highly available system.

The 32GiB encrypted dependency volume survives service restarts and host-image
replacement. Redpanda disables write caching and uses three topic partitions
with one replica. Its private advertised endpoint is the supporting host;
broker/cache/OTLP ports accept only application-host traffic. Grafana's password
is a separate SSM parameter/file, and its UI binds host loopback for SSM port
forwarding. Its volume, Tempo and Prometheus data, rotated collector logs and
warehouse outputs use explicit directories on that volume. Container memory
and CPU limits bound allocations; they do not establish measured capacity.
Watch disk usage and export evidence before teardown.

## Local validation and immutable packaging

Run without cloud credentials:

```sh
terraform -chdir=infra/full init -backend=false
terraform -chdir=infra/full fmt -check -recursive
terraform -chdir=infra/full validate
terraform -chdir=infra/full test
python -m unittest discover -s ops/cloud/full/tests -v
docker build -t auctionhouse:app-candidate .
docker build -f gateway/Dockerfile -t auctionhouse:gateway-candidate gateway
docker build -f warehouse/Dockerfile -t auctionhouse:warehouse-candidate warehouse
```

The full contract suite uses command doubles and Python standard-library
tests. Its warehouse TLS guard also needs the pinned warehouse environment;
without DuckDB that one case is explicitly skipped. Terraform tests use a
mock AWS provider. They exercise private ingress, exact host routing, immutable
SSM parameters, secret/state isolation, one/two actual app counts, preserved
shared resources, and the EC2 compressed-user-data size limit.

Resolve the two supplemental image IDs using `docker image inspect --format
'{{.Id}}' IMAGE`, then run:

```sh
python ops/cloud/full/test-images.py --gateway-image sha256:GATEWAY_ID \
  --warehouse-image sha256:WAREHOUSE_ID --output PRIVATE_NEW_DIRECTORY
```

This starts the non-root/read-only gateway and runs real DuckDB/dbt three
times against eight synthetic events in a labelled disposable volume. It
checks eight unique facts and 8/16/24 delivery attempts, then removes only its
own generated containers/volume. It does not prove real broker or RDS delivery.
The existing local application image/rollback/database-role checks remain
required for the application artifact.

## Approved AWS bootstrap

Review a selected-Region hourly and accidental-running estimate including
two/three EC2 hosts, all public IPv4s, EBS, ALB hours/LCUs, RDS/backups/master
secret, ECR, logs, data transfer and the external DNS/provider/backend. No NAT
gateway is created: hosts have public IPv4 only for outbound access, with no
public SSH/application/dependency ingress. Expiry tags and budget alerts do not
stop resources. ALB and RDS deletion protection default on.

Use a private backend file and private tfvars. Its key must be distinct from
the thin root and must exactly match `state_backend_key`. Backend bucket,
certificate, DNS and account-wide GitHub OIDC provider are external ownership;
this module does not create or destroy them. Apply only the explicitly reviewed
plan. Then save `terraform -chdir=infra/full output -json comparison_manifest`
to a private manifest and verify real account/state/backend identity:

```sh
python ops/cloud/full/state.py preflight --expected-account ACCOUNT \
  --session SESSION --profile auctionhouse --output PRIVATE_NEW_DIRECTORY
```

The preflight reads the actual initialized S3 backend metadata and requires
the declared key, encryption and lockfile; setting the variable alone cannot
configure a backend. Keep this evidence alongside the manifest.

Open an authorized SSM tunnel from the supporting host to RDS. Use the common
database bootstrap command documented in `docs/DEPLOYMENT.md`, adding
`--namespace full`. Full secrets live under `/auctionhouse/SESSION/full/`,
separate from thin credentials. Push the three tested immutable artifacts to
their output ECR repositories and save their **registry digests** in a private
release file with exactly `appDigest`, `gatewayDigest`, `warehouseDigest`.
Image config IDs, local image IDs and registry manifest digests are distinct;
resolve the pushed digest rather than substituting one for another.

Apply migrations from the app artifact once before granting warehouse table
access:

```sh
python ops/cloud/full/deploy.py --manifest PRIVATE_MANIFEST --release PRIVATE_RELEASE \
  --profile auctionhouse --mode migrate --schema-compatible --output PRIVATE_NEW_DIRECTORY
python ops/cloud/full/bootstrap-extra.py --profile auctionhouse --region REGION \
  --account-id ACCOUNT --session SESSION --expires-at RFC3339 \
  --master-secret-arn ARN --db-host RDS_HOST --ca-file PRIVATE_CA_BUNDLE
```

The second command runs in a private Linux/WSL shell with the tunnel open. It
creates a SELECT-only `ah_warehouse` role for five explicit outbox/cut/sink
tables and stages a separate random Grafana secret. It never grants warehouse
DDL or application writes. Retry reuses staged values; credential rotation is
a separate coordinated operation.

## Full release, rollback and inspection

```sh
python ops/cloud/full/deploy.py --manifest PRIVATE_MANIFEST --release PRIVATE_RELEASE \
  --profile auctionhouse --schema-compatible --output PRIVATE_NEW_DIRECTORY
```

Omit `--profile` for CI's temporary environment credentials. The orchestrator
checks the account, exact owned running instance roles and owned ALB/target
groups. It fetches the SSM documents, checks their fixed command, and pins the
numeric version before sending any command. The IAM role cannot run arbitrary
SSM shell documents or read database/Grafana secrets directly.

`.github/workflows/full-delivery.yml` keeps deployment off by default. Its
credential-free verification job builds and checks all three images, then saves
those exact artifacts. Deployment requires both explicit dispatch switches on
the `main` ref. Configure `AWS_ACCOUNT_ID`, `AWS_REGION` and
`AUCTIONHOUSE_FULL_DEPLOY_ROLE_ARN` as repository variables, and the exact
`comparison_manifest` JSON as `AUCTIONHOUSE_FULL_MANIFEST_JSON` in repository
secrets. The workflow checks ownership, expiry, account/Region, scoped role and
the three repository names before federation. It never performs Terraform apply
or account/bootstrap setup.

Dependencies start first, migrations run on one app host, then each app pair
drains for the configured 30 seconds and is replaced sequentially. One-host
deployments have a deliberate interruption. A successful candidate must pass
local readiness/UI/auth-boundary smoke and ALB health; final public readiness
uses normal TLS verification. A failure restores every touched previous image
pair, continuing recovery even if another rollback fails. Rejected first
releases are deactivated and left deregistered. Rollback status is explicit;
do not proceed after a failed recovery.

During a failed same-host candidate, the previous image pair reuses its still
mounted verified secret directory. Explicit rollback or reboot retrieves
current SSM configuration; this is an image rollback, not an automatic reversal
of credential/configuration changes or migrations. The restore service reloads
volatile secrets and the last verified release on reboot. Dependency versions
are pinned in the installed bundle, and changing persistent-service versions
requires a separate data-compatibility review.

The fixed inspection document reports only selected container identities,
resource limits, public runtime settings and status. It distinguishes configured
settings from actual container environment. The supporting host also reports
per-app auction-route HTTP counters from its private collector. Missing export
is `null`, not zero; allow the five-second export interval before comparing.
Neither the endpoint nor inspection prints credentials or a complete environment.

After authenticated auction activity and delivery settle, use the authorized
admin API to create an immutable cut, then run:

```sh
python ops/cloud/full/deploy.py --manifest PRIVATE_MANIFEST --release PRIVATE_RELEASE \
  --profile auctionhouse --mode warehouse --cut-id UUID --schema-compatible \
  --output PRIVATE_NEW_DIRECTORY
```

The warehouse uses RDS `verify-full` TLS, a UID10001 private password file and
the read-only role. It refuses incomplete source/sink identity or content cuts,
preserves the export and three replay/dbt reports on the dependency volume, and
refuses to overwrite an existing cut's output. This is a batch common-cut path;
Kafka consumer ownership and notification deduplication remain in Java.

## Teardown and residual data

`state.py inventory` saves both exact Terraform inventory and full-topology tag
inventory. After exporting evidence, review an `allow_destroy=true` change to
remove ALB/RDS protection. Prepare a private saved destroy plan:

```sh
python ops/cloud/full/state.py plan-destroy --expected-account ACCOUNT --session SESSION \
  --profile auctionhouse --var-file PRIVATE_TFVARS --plan PRIVATE_PLAN --output PRIVATE_NEW_DIRECTORY
```

Only after explicit approval of that exact plan/hash, run `apply-destroy` with
`--approved-plan-sha256 SHA256`; deletion of the dependency EBS volume also
requires `--accept-dependency-data-loss`. Create/review a snapshot beforehand
if retention is required. The guard rejects unowned resources, cross-root thin
tags, create/replace actions and untaggable children without exact owned parent
links. ECR repositories refuse deletion while images remain: review and purge
the exact owned image digests separately. Final RDS snapshots, bootstrap-owned
SSM parameters, retained EBS snapshots and external backend/certificate/DNS
must be inventoried and explicitly retained or removed. A successful Terraform
destroy or tag search alone is not proof of zero residual cost.

## Evidence limits

Local 2026-10-01 checks pass six Terraform mocked cases, 30 command-double
contracts and both real supplemental container smokes. Initial size/fixture
failures were retained privately. Actual cloud login/TLS/network and IAM
negative tests, SSM credential boundaries, complete trace/warehouse path,
rollback/reboot, cost inventory and teardown remain required. The one/two-host
measurement runner consumes prepared deployments; it does not provision them.

References: [ALB idle timeout](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/edit-load-balancer-attributes.html),
[target drain and stickiness](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/edit-target-group-attributes.html),
[SSM parameter handling](https://docs.aws.amazon.com/systems-manager/latest/userguide/documents-syntax-data-elements-parameters.html).
