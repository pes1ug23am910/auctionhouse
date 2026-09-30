# Image review: 1 October 2026

**Release image publication remains blocked by the configured vulnerability
policy.** Source publication and passing functional tests do not establish that
an image passed this gate. There are no project suppressions or accepted-risk
exceptions. Run both release workflows against the intended commit to obtain
current, exact-artifact results; the local observations below are development
candidate evidence, not a released artifact or an AWS scan.

The local review used Syft 1.52.0, Grype 0.119.0 and database schema 6.1.9 built
at 2026-09-30 06:32:47 UTC. Reports retain all severities, package versions,
advisory links, daemon image IDs, config and layer digests, and matcher
suppression reasons. Package-match totals can contain several binary packages
from one affected source package. They are not counts of distinct CVEs.

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
  while the dated scanner database still reports no fixed version. The raw
  scanner result remains visible and blocking; the review does not override it.

Remaining prerequisites include:

| Component | Evidence and required next step |
| --- | --- |
| Python 3.13.15 | [PSF's advisory](https://github.com/CVEProject/cvelistV5/blob/main/cves/2026/82xxx/CVE-2026-82049.json) identifies versions before 3.13.16 as affected by a tar extraction filter flaw. [Python 3.13.16](https://www.python.org/downloads/release/python-31316/) fixes it, but the official `python:3.13.16-slim-trixie` tag was unavailable during this review. Adopt its verified digest when available, rerun warehouse tests and scan it. Application reachability has not been established; that does not make the installed vulnerable library a false positive. |
| glibc on Trixie | [CVE-2026-19499](https://security-tracker.debian.org/tracker/CVE-2026-19499) remains marked vulnerable in the stable package. Debian classifies it as a minor issue without a stable security advisory; the configured High-severity scanner gate still blocks it. |
| util-linux on Trixie | [CVE-2026-78408](https://security-tracker.debian.org/tracker/CVE-2026-78408) remains marked vulnerable in the stable package; the fix is in unstable. Do not mix unstable packages into the runtime merely to satisfy the scanner. |
| Other OS matches | Review every retained finding, including ACL, ncurses, Perl, zlib and OpenSSL records. A nonroot, read-only container or absence of a known application exploit does not automatically waive a package finding. |

The telemetry agent's exact embedded JAR was checked against its official,
checksum-pinned 93-component SPDX inventory. That supplemental inventory had
no matches in this dated database. This is separate from the discovered image
inventory, and is not a claim that all shaded code or future advisories are
covered. Lower-severity image findings remain reported.

These checks do not cover the EC2 AMI, AWS ECR's independent findings, other
third-party support containers, cloud IAM/network behavior, or exploitability.
Those remain separate deployment checks.
