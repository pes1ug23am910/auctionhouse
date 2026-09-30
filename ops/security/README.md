# Release image security checks

Both manual release workflows scan their exact local image IDs before saving
or publishing the artifacts. The thin workflow scans the application; the full
workflow scans application, gateway and warehouse. Verify-only runs need no AWS
credentials and retain small security reports instead of image archives.
The [dated image review](REVIEW.md) records the known release blockers and
supported remediations; a functional build alone is not a release approval.

```sh
python -m unittest discover -s ops/security/tests -v
python ops/security/scan-images.py --revision FULL_COMMIT_SHA \
  --image app=sha256:IMAGE_ID --cache PRIVATE_TOOL_CACHE --output NEW_REPORT_DIRECTORY
```

Repeat `--image` for `gateway` and `warehouse`. Tags and registry fallbacks are
rejected. Every image's OCI source label must match the commit.
`--baseline` explicitly labels older local images and cannot establish current
release provenance; release workflows do not use it. The helper downloads
official Grype 0.119.0 and Syft 1.52.0 archives
for Linux AMD64 and verifies their pinned SHA256 checksums before
extracting only the executable. It reads the local Docker daemon and uses the
downloaded vulnerability database for matching. External Maven enrichment and
application-update checks are disabled. No source, image, SBOM or credentials
are uploaded to a matching service.

Run the scanner on Linux AMD64, as both release workflows do. Native Windows
scanning is rejected: Syft 1.52.0's daemon layer cache creates filenames that
Windows does not accept. The Python contract tests remain portable. A local
Linux scanner container can read the Docker socket, but that socket grants
host-level access; use only a trusted local image and the reviewed helper.

The scan requires a recognized, versioned distro, OS packages and the expected
Java/agent, Node/npm or Python package coverage for each image. It covers the
final filesystem's discovered packages, including indexed Java archives.
The gateway inventory must identify Node and the application package; its
runtime image deliberately omits unused npm/npx/yarn. The application also
matches the telemetry agent's official 93-component SPDX SBOM, verifying both
the embedded agent JAR and upstream SBOM archive against pinned SHA256 hashes.
Those are declared upstream components, reported separately from Syft's
discovered packages because the shaded agent is not fully discoverable.
It is an inventory/advisory comparison, not a
reachability assessment or proof that every embedded component was identified.
The scanner database must pass its checksum and five-day age checks. Scanner,
download, database and image-identity failures stop release; local ignore rules
and inherited scanner configuration cannot silently change the result.

The gate blocks **High, Critical and Unknown** severities, including findings
without a fix. Low, Medium and Negligible findings remain reported and require
review; a passing gate does not mean the image has no vulnerabilities. There
are no project exceptions. Remediate supported fixes first. Any future accepted
finding needs an explicit review of the exact package/version, advisory,
applicability, expiration and compensating controls; do not add blanket ignore
patterns or discard unfixed findings.

Reports bind the commit, exact daemon image IDs, image-config digests and
ordered root-filesystem layer digests, scanner binary/archive checksums,
database metadata, component inventory and findings hashes. Each image has a
normalized inventory (`*.sbom.json`), a CycloneDX 1.6 component SBOM (`*.cdx.json`)
and all matched findings (`*.vulnerabilities.json`). Built-in matcher exclusions
(such as a distro's fixed/not-affected record) do not count as policy violations;
their full findings and applied-rule reasons remain separately reviewable.
These are distinct from project exceptions, of which there are none.
Container configuration, environment variables and
scanner configuration are not copied into reports. These are package facts,
not an export of files from the image. Failed scans also preserve the summary.
The agent's official SPDX document and supplemental findings are retained as
`app.agent.spdx.json` and `app.agent.vulnerabilities.json`.

This gate covers only these three built release images. Pinned third-party
support services, actual AWS ECR findings, selected EC2 AMI patch review and
live IAM/network checks remain separate deployment prerequisites. Enabling ECR scan-on-push
alone is not evidence that its results were reviewed.

Sources: [Grype releases](https://github.com/anchore/grype/releases/tag/v0.119.0),
[Syft releases](https://github.com/anchore/syft/releases/tag/v1.52.0),
[Grype configuration](https://oss.anchore.com/docs/reference/grype/configuration/),
[Syft output formats](https://oss.anchore.com/docs/guides/sbom/formats/).
