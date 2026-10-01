# Image review: 1 October 2026

**Release image publication remains blocked by the vulnerability policy.**
The `bd78f33` verification completed functional checks and all image scans;
112 High package/advisory matches remain, including two in the telemetry agent.
The earlier thin verification pass is historical. There are no project suppressions or
accepted-risk exceptions. These are build and scan results, not a deployment
or an AWS scan.

## Completed rebuilt-image scan at `bd78f33`

The [1 October verification](https://github.com/pes1ug23am910/auctionhouse/actions/runs/36852257229)
of [commit bd78f33](https://github.com/pes1ug23am910/auctionhouse/commit/bd78f33a63641c4e57c0a6804e9dcb135f321e01)
built and tested all three images, including application rollback and actual
gateway/warehouse container checks. Every image passed exact config-digest,
ordered-layer, platform and expected package-coverage checks. The official
93-component agent supplement also completed with its JAR/archive checks.
Scanning finished without a helper error and rejected the release under the
unchanged severity policy. Cleanup passed; image export and deployment were
skipped.

| Rebuilt inventory | Packages | High / blocking matches | Medium | Low | Negligible |
| --- | ---: | ---: | ---: | ---: | ---: |
| Application, discovered packages | 203 | 0 | 66 | 6 | 3 |
| Official agent supplement | 93 | 2 | 0 | 0 | 0 |
| Gateway | 82 | 51 | 49 | 10 | 45 |
| Warehouse | 200 | 59 | 61 | 11 | 69 |

There were no Critical or Unknown matches. The application release has two
blocking matches in total, both from its agent supplement. The complete
release has **112 blocking matches across 16 distinct advisory IDs**; these
are different measures, and the agent is counted once. Syft 1.52.0 and Grype
0.119.0 used the valid schema 6.1.9 database built at
**2026-10-01 06:33:48 UTC**. The retained artifact archive, reports, inventories
and source/image identities were checked against their recorded hashes.

Fresh inventories confirm application OpenSSL `3.0.13-0ubuntu3.16` and both
supplementary images' PCRE2 `10.46-1~deb13u3`. `CVE-2026-84782` and
`CVE-2026-103111` no longer appear. Jackson, Python and remaining OS findings,
including the zlib classification conflict, still block release. The vendor
prerequisite checks below remain dated observations; this build did not check
for another upstream release or grant an exception.

## Rebuilt images and incomplete scan at `3d7cb6e`

The [1 October verification](https://github.com/pes1ug23am910/auctionhouse/actions/runs/36850129531)
built all three images from [commit 3d7cb6e](https://github.com/pes1ug23am910/auctionhouse/commit/3d7cb6e72f888c68c932cf83d08f780a1c038c16).
Backend/PostgreSQL correctness, application packaging and rollback, and actual
gateway/warehouse container checks passed. Fixture cleanup passed; image export
and deployment were skipped.

The new application inventory confirms `libssl3t64` and `openssl`
`3.0.13-0ubuntu3.16`. Its 203 discovered packages had zero High, Critical or
Unknown matches, 66 Medium, six Low and three Negligible matches against the
database built at 2026-10-01 06:33:48 UTC. Image/config identity, ordered layers
and application package coverage passed. The two previous application OpenSSL
matches were absent. This is only the discovered inventory: the agent
supplement did not finish, so the application release did not pass its gate.

The helper raised a `TypeError` while normalizing a nonempty official-agent
report whose optional package locations were `null`. Gateway and warehouse
scanning had not started. This run therefore has **no complete three-image
vulnerability result**, and failed because of the helper defect rather than
a completed policy evaluation. Build logs confirm PCRE2 `10.46-1~deb13u3` was
installed in both supplementary images; their rebuilt vulnerability counts
remain unverified.

The helper now accepts missing/null location metadata while rejecting malformed
package and location shapes. Replaying the retained real agent report preserves
both High findings, and the focused contracts pass on Windows and Linux.
Identity, checksum, package-coverage and severity rules are unchanged. The
subsequent `bd78f33` verification above completed those scans; the failed run
and its partial results remain preserved.

## Earlier completed hosted scan at `2eca9b0`

Hosted verification of [commit 2eca9b0](https://github.com/pes1ug23am910/auctionhouse/commit/2eca9b0d38058ac2dad1d8d71c41a5c4235a8386)
completed on 1 October 2026. The [thin run](https://github.com/pes1ug23am910/auctionhouse/actions/runs/36795616768)
passed. The [full run](https://github.com/pes1ug23am910/auctionhouse/actions/runs/36795613279)
passed its functional, database, rollback and container checks, then stopped
at the vulnerability policy after scanning all three images. Both deployment
jobs were skipped; only small security reports were uploaded.

| Full-run image | Packages | Critical | High | Unknown | Medium | Low | Negligible | Blocking matches |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Application | 203 | 0 | 0 | 0 | 62 | 6 | 3 | 0 |
| Gateway | 82 | 0 | 54 | 8 | 54 | 16 | 44 | 62 |
| Warehouse | 200 | 0 | 65 | 12 | 69 | 20 | 68 | 77 |

Every hosted image passed exact config-digest identity, ordered layer, platform
and package-coverage checks. Each run's retained reports bind full image IDs
and SBOM/findings hashes to the source revision. The separately built thin
application had the same package and finding counts; it is a different image
ID. Both application images' verified 93-component agent inventories had zero
matches in the dated database. No scanner error caused the full-run rejection.

Hosted scans and the earlier local review used Syft 1.52.0, Grype 0.119.0 and database schema 6.1.9 built
at 2026-09-30 06:32:47 UTC. Reports retain all severities, package versions,
advisory links, daemon image IDs, config and layer digests, and matcher
suppression reasons. Package-match totals can contain several binary packages
from one affected source package. They are not counts of distinct CVEs.
Earlier local scans remain development-candidate evidence; the linked hosted
runs establish the images built from `2eca9b0`.

## Retained-inventory rematch with the 1 October database

The same Grype 0.119.0 matched the retained full-run inventories against the
checksum-verified schema 6.1.9 database built at **2026-10-01 06:33:48 UTC**.
No images were rebuilt. Original report hashes, image/config identities,
ordered layers and coverage records were verified before reuse.

| Retained inventory | Packages | High / blocking matches | Medium | Low | Negligible |
| --- | ---: | ---: | ---: | ---: | ---: |
| Application, discovered packages | 203 | 2 | 66 | 16 | 3 |
| Official agent supplement | 93 | 2 | 0 | 0 | 0 |
| Gateway | 82 | 52 | 49 | 10 | 45 |
| Warehouse | 200 | 60 | 61 | 11 | 69 |

There were no Critical or Unknown findings in this rematch. The application
and agent contribute four blocking matches together. Across these inventories,
distinct blocking advisory IDs changed from 20 to 18; package-match totals
changed from 139 to 116. Lower totals do not establish release acceptance.

The retained compact CycloneDX documents omit CPE metadata. Regenerating
missing CPEs with Grype's `--add-cpes-if-none` and the recorded distributions
first reproduced every original match key, severity and matcher-suppressed
key against the old database (71 application, 176 gateway, 234 warehouse and
zero agent matches). The same method was then used for the new database.
That observed equivalence cannot prove every future CPE identifier identical
to the original scan. These are dated results for the retained `2eca9b0`
inventories, not a fresh live inventory or a scan of the current source HEAD.
Changed artifacts still require the full image build, checks and scan.

Seven previously reported OpenSSL advisory IDs no longer match the fixed
Debian packages. Six disappear from the blocking set entirely;
`CVE-2026-84782` now matches the application's older Ubuntu OpenSSL instead.
New blocking IDs also include `CVE-2026-102010`, `CVE-2026-103111` and the two
Jackson advisories below. The zlib classification conflict and Python finding
remain blocking. The severity policy, absence of project ignores and retained
matcher-suppression reporting are unchanged.

Supported fixes applied:

- The application upgrades Ubuntu Noble `perl-base` to
  `5.38.2-3.2ubuntu0.6`, fixing the versions identified by
  [CVE-2026-19487](https://ubuntu.com/security/CVE-2026-19487) and
  [CVE-2026-15534](https://ubuntu.com/security/CVE-2026-15534).
- Gateway and warehouse runtime bases move from Debian Bookworm to the
  digest-pinned official Trixie images, retaining Node 24 and Python 3.13.
  Trixie contains the maintained fixes for
  [glibc CVE-2026-5450](https://security-tracker.debian.org/tracker/CVE-2026-5450)
  and [util-linux CVE-2026-53613](https://security-tracker.debian.org/tracker/CVE-2026-53613).
- The gateway runtime removes unused npm/npx/yarn. The builder retains npm
  and runs its tests; production starts directly with Node and has no external
  npm runtime dependencies.
- The gateway and warehouse explicitly install Debian's
  `3.5.7-1~deb13u3` OpenSSL security packages. The primary Debian tracker lists
  this fix for [CVE-2026-35189](https://security-tracker.debian.org/tracker/CVE-2026-35189)
  and [CVE-2026-72897](https://security-tracker.debian.org/tracker/CVE-2026-72897),
  while the original 30 September scanner database reported no fixed version.
  The 1 October rematch now recognizes these Debian fixes; the historical raw
  results are retained.

A follow-up on 1 October reviewed all 20 distinct advisories behind the
retained blocking matches, including all 19 Debian records. Seven OpenSSL
records now identify the already-installed `3.5.7-1~deb13u3` as fixed:
`CVE-2026-35189`, `CVE-2026-35191`, `CVE-2026-42772`, `CVE-2026-54873`,
`CVE-2026-72897`, `CVE-2026-84782` and `CVE-2026-84784`.
[Debian's security update](https://security-tracker.debian.org/tracker/DSA-6531-1)
is the package-level remediation; the earlier scanner database's matches
remain unchanged in the recorded results. No additional stable-package fix
was identified for the remaining Debian matches during that initial review.
The later rematch identified additional advisories with the supported fixes
listed below. The official Python image tag still returned HTTP 404 at
09:52 UTC, and the current Node 24/Python 3.13 Trixie aliases retained their
existing pinned digests.

One additional applicability conflict requires resolution. Both supplemental
images contain `zlib1g` version `1:1.3.dfsg+really1.3.1-1+b1`. The
[CNA record for CVE-2026-85091](https://github.com/CVEProject/cvelistV5/blob/main/cves/2026/85xxx/CVE-2026-85091.json)
identifies versions 1.3.1.2 through 1.3.2 as affected, with other versions
unaffected by default. The corresponding
[Debian source file](https://sources.debian.org/src/zlib/1%3A1.3.dfsg%2Breally1.3.1-1/gzwrite.c/)
is byte-for-byte identical to upstream 1.3.1 and lacks the named `gz_vacate`
function, while the Debian tracker still marks that package vulnerable.
This is a version/source adjudication conflict, not a conclusion that the
compiled image is universally safe. No exception has been added: both zlib
matches remain blocking until the conflicting classification is resolved.

Remaining prerequisites include:

| Component | Evidence and required next step |
| --- | --- |
| Application OpenSSL — verified | [Ubuntu's CVE-2026-84782 record](https://ubuntu.com/security/CVE-2026-84782) fixes `libssl3t64` and `openssl` in `3.0.13-0ubuntu3.16`. The completed `bd78f33` inventory confirms both versions and no longer reports those two matches. The separate agent findings remain blocking. |
| Gateway and warehouse PCRE2 — verified | [Debian's CVE-2026-103111 record](https://security-tracker.debian.org/tracker/CVE-2026-103111) fixes `libpcre2-8-0` in `10.46-1~deb13u3`. Both fresh `bd78f33` inventories confirm that stable version and no longer report this advisory. Other findings remain blocking. |
| Telemetry agent Jackson | The official agent inventory declares `jackson-databind` `2.22.2`. [GHSA-cxp5-3px4-pw24](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-cxp5-3px4-pw24) and [GHSA-wv8q-qhhj-9h54](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-wv8q-qhhj-9h54) identify `2.22.3` as fixed. The official [latest agent release](https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest) remained `2.31.1`, with the exact installed JAR and SBOM digests. No released agent containing the fix was found; await a supported release, verify its JAR and declared inventory, then test and scan it. Do not replace shaded libraries manually. Application reachability has not been established and no exception is granted. |
| Python 3.13.15 | [PSF's advisory](https://github.com/CVEProject/cvelistV5/blob/main/cves/2026/82xxx/CVE-2026-82049.json) identifies versions before 3.13.16 as affected by a tar extraction filter flaw. [Python 3.13.16](https://www.python.org/downloads/release/python-31316/) fixes it, but the official `python:3.13.16-slim-trixie` tag was unavailable during this review. Adopt its verified digest when available, rerun warehouse tests and scan it. Application reachability has not been established; that does not make the installed vulnerable library a false positive. |
| glibc on Trixie | [CVE-2026-19499](https://security-tracker.debian.org/tracker/CVE-2026-19499) remains marked vulnerable in the stable package. Debian classifies it as a minor issue without a stable security advisory; the configured High-severity scanner gate still blocks it. |
| util-linux on Trixie | [CVE-2026-78408](https://security-tracker.debian.org/tracker/CVE-2026-78408) remains marked vulnerable in the stable package; the fix is in unstable. Do not mix unstable packages into the runtime merely to satisfy the scanner. |
| ACL on Trixie | [CVE-2026-54369](https://security-tracker.debian.org/tracker/CVE-2026-54369) includes pathname-based functions in the installed `libacl1` library. It cannot be dismissed even if standalone ACL utilities are absent. Debian says the fix requires the newer ABI and is intended for a later point release. |
| GCC runtime packages | [CVE-2026-102010](https://security-tracker.debian.org/tracker/CVE-2026-102010) matches the installed `gcc-14-base`, `libgcc-s1` and `libstdc++6` `14.2.0-19`. Debian still marks the stable source package vulnerable without a fixed version. |
| Other OS matches | Review every retained finding, including ncurses, Perl and zlib records. A nonroot, read-only container or absence of a known application exploit does not automatically waive a package finding. |

The telemetry agent's exact embedded JAR was checked against its official,
checksum-pinned 93-component SPDX inventory. That supplemental inventory had
no matches in the original 30 September database and two High matches in both
the 1 October rematch and completed `bd78f33` scan. This is separate from the discovered image
inventory, and is not a claim that all shaded code or future advisories are
covered. Lower-severity image findings remain reported.

These checks do not cover the EC2 AMI, AWS ECR's independent findings, other
third-party support containers, cloud IAM/network behavior, or exploitability.
Those remain separate deployment checks.
