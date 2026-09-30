# Delivery and recovery

The release image contains the Java application, database migrations and the built React client. It runs as UID 10001 with a read-only filesystem. Image stages and the local PostgreSQL/Caddy dependencies use immutable SHA-256 digests. Cloud runtime processes use the DML database account with Flyway disabled; deployment invokes the migration command from the same image with a separate schema-owner credential.

The 2026-10-01 local release check exercised image `sha256:ede2e1ede1d58fee7b1150898be687c01e07b0a6cc420defd3fd493df78e75d5`: migrations V1-V5, runtime database privilege denials, failed-candidate rollback to the exact prior digest, bundled assets and Chromium page loading. Its optional telemetry agent initialized as the non-root image user, matched its pinned checksum and refused startup without an explicit collector endpoint. These observations apply to that local image; real cloud delivery remains unverified.

## Local release fixture

Prerequisites: Docker with Linux containers and PowerShell 7. The image build runs frontend and Java unit tests. PostgreSQL/Testcontainers integration tests run separately before releasing an artifact.

```powershell
./gradlew.bat --no-daemon --no-watch-fs test integrationTest
docker build --build-arg SOURCE_REVISION=(git rev-parse HEAD) -t auctionhouse:candidate .
$image = docker image inspect auctionhouse:candidate --format '{{.Id}}'
$env:AUCTIONHOUSE_DB_PASSWORD = 'choose-a-disposable-local-fixture-password'
./ops/Deploy-Local.ps1 -Image $image
./ops/Test-LocalRollback.ps1 -GoodImage $image -Port 18081
./ops/Test-DatabaseRoles.ps1 -Image $image
```

`compose.app.yaml` is standalone: do not merge it with development `compose.yaml`. It publishes only the app on loopback, uses a private internal PostgreSQL network, and creates a separate `auctionhouse-release` database volume. The local fixture intentionally uses one development database owner; the cloud path uses separate roles. A local demo login is available only if an explicit BCrypt `AUCTIONHOUSE_DEMO_PASSWORD_HASH` is supplied. No production credential or demo password is embedded.

Release acceptance checks database readiness, the bundled frontend, CSRF response and anonymous-session rejection. Existing HTTP integration tests provide the authenticated auction/OIDC correctness checks. `Test-LocalRollback.ps1` builds a deliberately faulty image that listens on the wrong port, verifies its rejection, then checks that the exact prior image and release record survive. A failed migration never starts a candidate. Runtime rollback does not reverse migrations. `Test-DatabaseRoles.ps1` uses a fresh database in that fixture to verify the actual bootstrap SQL plus migration image: DML succeeds, while schema creation/alteration, Flyway-history mutation and database creation are denied. It leaves the test DB for inspection and refuses to overwrite an existing one.

Release state is private under `.release/`; do not commit it. Inspect fixture containers before cleanup. To remove the fixture including its disposable database:

```powershell
$env:AUCTIONHOUSE_IMAGE = $image
# Use the exact fixture project that was created; this deletes that fixture's data.
docker compose --project-name auctionhouse-rollback-test -f compose.app.yaml down -v
```

## Optional telemetry in the image

The image includes checksum-verified OpenTelemetry Java agent 2.31.1 at `/opt/otel/opentelemetry-javaagent.jar` and `observability/agent.properties`. Instrumentation is off by default. Enable it with `AUCTIONHOUSE_OTEL_ENABLED=true` and an explicit private `OTEL_EXPORTER_OTLP_ENDPOINT` (for example the collector service's `http://collector:4318` on a private Docker network). The entrypoint refuses opt-in startup without an endpoint. No collector port is exposed by the release fixture. The migration command does not start the agent. Agent configuration avoids capturing HTTP headers and sanitizes SQL; see the observability documentation for the measured tracing path and resource profile.

## AWS project prerequisites

`infra/` is a locally reviewable Terraform module. It has not, by itself, established AWS application delivery. Use the user's selected Region from **AWS Settings → View all projects → Overview → Additional Info → Region**, or the explicitly configured CLI profile if that setting cannot be read. The new AWS experience restricts regional resources to that Region. The module requires the Region and two available zones; it does not infer them from the console URL.

Before any cloud plan/apply, confirm:

- The AWS project is usable, the `auctionhouse` profile identifies the intended project/account ID, and the current Free/Paid plan and spend-limit status are known. Resolve a suspended project through AWS support; do not try another Region or upgrade the plan as a workaround.
- The operator has explicitly approved the resource plan, usage duration, cost estimate, expiry timestamp, budget alert recipient and spend limits. Budget alerts can be delayed and are not a hard cap.
- An owned DNS hostname points to the module's Elastic IP; its TLS validation can receive ports 80 and 443. No domain purchase is performed by these scripts.
- A real HTTPS OIDC provider/client is configured with the exact `https://HOST/login/oauth2/code/keycloak` callback. The client uses authorization code + PKCE; local Keycloak fixture passwords must not be used on the public deployment.
- The exact repository/protected branch is approved for GitHub OIDC. The project bootstrap operator has created or reviewed the account-wide GitHub provider. This module consumes its ARN and never destroys it.
- The reviewed AL2023 x86_64 AMI with SSM Agent 3.3.2746.0 or newer (required for environment-variable document interpolation), RDS PostgreSQL minor version and bounded EC2/RDS classes are available in the selected Region. Inspect image scan findings and approve any accepted vulnerabilities before publishing the artifact.

The new experience manages human access. This module does not create IAM users or alter `/managed/` roles. Its optional conventional MFA bootstrap role defaults to absent: a social-sign-in session must not be assumed to expose `aws:MultiFactorAuthPresent`. Use the existing approved managed human access for one-time bootstrap and review the exported scoped policy contract. GitHub deployment cannot read the RDS master secret or invoke arbitrary SSM shell commands.

## Thin architecture and cost boundary

The initial module creates one bounded x86 EC2 host, one encrypted single-AZ RDS PostgreSQL database in two private DB subnets, immutable ECR, seven-day application and RDS PostgreSQL logs, scoped service roles, one release SSM document, an Elastic IP and account-wide budget notifications. No SSH ingress, public PostgreSQL port, NAT gateway, load balancer or VPC endpoint baseline is created. RDS requires TLS with hostname/CA validation. Containers cannot obtain the EC2 instance profile through IMDS because IMDSv2 is required with hop limit one; no container uses host networking.

The public host serves Caddy on 80/443; Caddy terminates automatic HTTPS and proxies the private application network. The instance reaches AWS APIs over outbound HTTPS. Caddy certificate data is persistent. A systemd boot hook reloads secrets from SSM and restores the last verified release after reboot. AWS deployment scripts are installed from the reviewed Terraform source, rather than downloaded from a moving Git branch.

This is a **thin topology**, including the complete web application. Full broker/cache/gateway/warehouse/observability deployment and the multi-instance/load-balancer experiment remain separate required evidence. Enable each only after its resource profile, private endpoints, failure behavior and cost are measured; do not squeeze the entire stack into the thin host or infer production capacity from local passing tests.

A cost review must include EC2 runtime and standard CPU-credit behavior, EBS, the public IPv4/Elastic IP, RDS runtime/storage/backups/final snapshots, managed master-secret storage/API calls, ECR image retention/scanning, logs, data transfer and DNS/provider charges. RDS is single-AZ for the bounded experiment, not a high-availability claim. Storage autoscaling is capped at the declared 20 GiB. RDS deletion protection defaults on; a final snapshot is retained. These retained resources may continue to cost money after the app stops.

The monthly budget input deliberately has no default. Calculate both the experiment cost (approved hours) and a 730-hour accidental-running estimate using current selected-Region prices. Keep the quote date and rates with the private release evidence. The expiry tag is ownership metadata and a teardown deadline, not an automatic shutdown mechanism. Free-plan credits are not a substitute for an approved estimate.

## Terraform and ownership

Pinned tooling: Terraform 1.16.4, AWS provider 6.66.0. The provider lockfile belongs in source control; local state, plans, secrets and real tfvars do not. Use a separate encrypted/locked backend for a real shared deployment; project-level state storage remains an explicit bootstrap prerequisite, outside this project module's teardown. The module declares an S3 backend with required values omitted. Review `infra/backend.hcl.example`, configure an already-approved encrypted/versioned bucket and a unique session key, then initialize with `-backend-config=PRIVATE_BACKEND_CONFIG`. `use_lockfile=true` enables state locking. Use `AWS_PROFILE=auctionhouse` and verify the intended project before a real init/plan; local checks deliberately use `-backend=false`.

```powershell
terraform -chdir=infra init -backend=false
terraform -chdir=infra fmt -check -recursive
terraform -chdir=infra validate
terraform -chdir=infra test
```

The tests use Terraform's mocked provider and make no AWS API calls. They check private/encrypted/deletion-protected DB settings, master-secret handling, container metadata boundary, ECR immutability, default absence of a human role, ownership metadata and digest-only SSM input; negative cases reject missing spend approval, cross-Region zones and wildcard GitHub repository subjects. These checks are not substitutes for real cloud network/IAM negative tests.

Copy `infra/terraform.tfvars.example` to a private tfvars file only after resolving the required inputs. Its placeholder budget and Region are intentionally invalid. Provider `allowed_account_ids` pins the project identity. Every supported resource receives `Project=auctionhouse`, `SessionId`, `Owner`, `ExpiresAt`, `ManagedBy=Terraform`. Keep the state unique to this module/session. The shared infrastructure integration is an interface: no sibling repository is required or modified.

## Database bootstrap and secrets

RDS generates and manages its master secret in Secrets Manager; no plaintext master password enters Terraform. The operator uses a short-lived authorized session and SSM port forwarding to the private RDS host. The host profile and GitHub role cannot read the master secret. The bootstrap program runs in a private Linux/WSL operator shell with AWS CLI and `psql`, and verifies the expected AWS account ID. Use an explicitly downloaded official RDS Region CA bundle and keep its checksum in evidence.

```sh
# Keep this session open. Use reviewed Terraform output values, never guessed IDs.
aws --profile auctionhouse --region SELECTED_REGION ssm start-session \
  --target INSTANCE_ID --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters '{"host":["RDS_HOST"],"portNumber":["5432"],"localPortNumber":["15432"]}'

python3 ops/cloud/bootstrap-database.py --profile auctionhouse \
  --region SELECTED_REGION --account-id EXPECTED_ACCOUNT --session SESSION \
  --expires-at RFC3339_EXPIRY --master-secret-arn MASTER_SECRET_ARN \
  --db-host RDS_HOST --ca-file REGION_CA.pem --issuer https://ISSUER --client-id PUBLIC_PKCE_CLIENT
```

The bootstrap creates `ah_migrator` (schema ownership) and `ah_runtime` (DML/sequence use, no schema creation). It stages generated secrets as Standard SSM SecureString parameters `/auctionhouse/SESSION/migration` and `/auctionhouse/SESSION/runtime`; retry reuses the staged values. Runtime is a small explicit JSON allowlist containing the DB password and OIDC client/provider fields. No password appears in argv, source, log output or Terraform state. The deployment host reads only those two exact parameters. The migration command revokes runtime access to Flyway history after applying migrations.

Runtime credentials are mounted only into the app; migration credentials are mounted only into a short-lived migration container. Host root/deployment remains a privileged boundary. Runtime secret files use a tmpfs path, UID 10001 and mode 0400. Do not enable shell tracing or dump Docker inspection output/environment in evidence. Rotate credentials through a separately reviewed coordinated procedure; the one-time bootstrap does not silently rotate existing secrets.

## Automated release and rollback

`.github/workflows/delivery.yml` is manually dispatched. Its default performs local validation only. It tests the source, builds one image, smoke-checks that image with private local PostgreSQL, and transfers the same saved artifact to the deploy job. Deployment also requires the explicit schema-compatibility input and the exact protected `main` ref. Configure `AWS_REGION`, `AWS_ACCOUNT_ID`, `ECR_REPOSITORY`, `AUCTIONHOUSE_INSTANCE_ID`, `AUCTIONHOUSE_RELEASE_DOCUMENT` and `AUCTIONHOUSE_DEPLOY_ROLE_ARN` as reviewed repository variables. The OIDC trust uses exact `aud=sts.amazonaws.com` and `sub=repo:OWNER/REPO:ref:refs/heads/main`; adding a GitHub Environment changes the subject and requires a coordinated trust-policy review.

A release tag is immutable and unique to source SHA/run/attempt. The host receives only its resolved SHA-256 digest through a fixed SSM document, pulls it before touching the running app, applies migrations once, starts the candidate and checks local readiness plus public TLS smoke. If candidate startup or smoke fails, it restores the previous image and verifies it; failure is still reported to CI. Logs contain image references and sanitized outcomes, not tokens or DB passwords. A persistent current/previous image record supports explicit rollback with the same SSM document (`Mode=rollback`), which skips migrations.

Migration policy is expand/contract: add compatible structures first, deploy readers/writers, backfill with explicit bounds, and remove old structures only after the rollback window has closed and a new baseline is declared. Before selecting `schema-compatible`, verify the previous image against the post-migration schema. A failed migration is investigated and repaired forward; rollback never invokes Flyway clean, reverses SQL automatically, or restores a stale DB snapshot over newer accepted bids. An irreversible schema change must not use this automatic rollback path.

## Inventory and teardown

`Inventory-Aws.ps1` checks account ID and queries exact project/session tags plus the Terraform resource manifest. Tagging APIs do not cover every resource type, so preserve the state inventory too. If global services were added later, inspect their WAF/Logs dependencies in the selected Region and us-east-1 as the new-experience rules require; this module creates no global WAF or cross-Region resources.

`Teardown-Aws.ps1` defaults to an inventory and saved destroy plan. Execution requires approval of that exact plan SHA-256, validates the project state and ownership, and rejects non-delete actions. Every taggable resource must retain exact project/session tags; only the module's named untaggable policy/association bindings are exempt, and they must reference the same plan's tagged module parents. `ops/Test-TeardownGuard.ps1` checks accepted and rejected plan cases using local command doubles, without invoking AWS or Terraform executables. First review a separate `allow_destroy=true` apply to remove RDS deletion protection. ECR deliberately refuses forced repository deletion: inspect and explicitly purge only this session's image digests with `Purge-OwnedAwsData.ps1 -Kind EcrImages` before deleting the repository.

The purge script is read-only by default; destructive execution requires both `-Execute` and `-AcceptDataLoss`, verifies the AWS account and exact `Project`/`SessionId` resource tags, and addresses only the derived repository, two SSM parameters, or final snapshot. After Terraform destroy, inventory residual resources and decide explicitly whether to retain or remove the final snapshot and bootstrap-owned SSM secrets. Do not delete a shared GitHub provider, shared state backend, unrelated project resources or managed human-access roles. Preserve the final inventory and charges review as teardown evidence.

## Evidence still required from real AWS

The separate [full topology](../infra/full/README.md) and its [operator commands](../ops/cloud/full/README.md) add AWS ALB, one/two actual app hosts, a shared dependency host and RDS, supplemental gateway/warehouse images, pinned SSM release/inspection, and a dedicated full-state teardown guard. Local Terraform mock, script-contract and container-packaging checks pass; these artifacts do not establish an actual full cloud deployment. The thin root remains available independently.

A completed A7 release needs the approved cost estimate, actual resource/ownership manifest, app digest and migration record, valid public TLS, real issuer login, runtime DB privilege denial, public DB/SSH denial, mismatched OIDC repository/ref rejection, failed-candidate recovery to the prior digest, reboot recovery, measured resource/log behavior and a verified final teardown/residual inventory. Thin and full deployments must be labelled separately. Local image/Compose/mock tests do not establish any of these cloud observations.

## Official references

- [AWS new-experience constraints](https://raw.githubusercontent.com/aws/agent-toolkit-for-aws/refs/heads/main/rules/aws-starter-rules.md), [supported services](https://docs.aws.amazon.com/accounts/latest/reference/supported-services-sign-up-new.html), and [managed project policies](https://docs.aws.amazon.com/accounts/latest/reference/scps-and-rcps-for-projects.html).
- [GitHub OIDC IAM trust](https://docs.aws.amazon.com/IAM/latest/UserGuide/id_roles_create_for-idp_oidc.html) and [RDS PostgreSQL release calendar](https://docs.aws.amazon.com/AmazonRDS/latest/PostgreSQLReleaseNotes/postgresql-release-calendar.html).
- [AWS Budgets behavior and delays](https://docs.aws.amazon.com/cost-management/latest/userguide/budgets-managing-costs.html), [Terraform release binaries](https://releases.hashicorp.com/terraform/1.16.4/) and [AWS provider registry](https://registry.terraform.io/providers/hashicorp/aws/6.66.0).

- [SSM least-privilege session policies](https://docs.aws.amazon.com/systems-manager/latest/userguide/getting-started-restrict-access-examples.html), [safe document parameter interpolation](https://aws.amazon.com/blogs/mt/aws-systems-manager-run-command-now-supports-interpolating-parameters-into-environment-variables/) and [Terraform S3 backend locking](https://developer.hashicorp.com/terraform/language/backend/s3).
