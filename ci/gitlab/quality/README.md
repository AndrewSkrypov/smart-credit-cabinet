# GitLab quality runners

Use a dedicated Linux VM or a replacement VM per job for the `cabinet-ci`
tag. Shell jobs execute MR code. Limit the runner to this GitLab project, disable
untagged jobs, use one job per worker Docker daemon and keep production credentials,
deploy keys, cluster access and other runners' sockets outside the job environment.
Run several independent workers when more parallel capacity is needed. A second
socket on a shared shell account does not isolate MR code.

Provide Bash, GNU coreutils, Git, Docker Engine and Compose v2.24.4 or later,
JDK 21 with `JAVA_HOME`, Node 20, Helm 3.19+ or 4, Python 3 with venv, curl, flock, and PostgreSQL 16
client binaries (`psql`, `pg_dump`, `pg_restore`, `pg_config`). Install Playwright
Chromium's Linux system packages in the worker image using the repository's
locked `e2e` package, for example `npm ci --prefix e2e` then
`cd e2e && npx playwright install-deps chromium` during provisioning.
The test job installs the matching Chromium revision as the runner user.
Allow downloads from the dependency repositories, container registries,
the Apache Kafka archive and ClamAV signatures. Size the worker for the
multi-replica Compose stack.

On the dedicated VM configure the quality daemon with the engine label
`cabinet-ci=true`. Set the runner's `config.toml` environment entry to
`CABINET_CI_DOCKER_HOST=unix:///var/run/docker.sock` when that socket belongs to
the dedicated daemon, or to the worker's configured Unix socket otherwise.
Use a runner concurrency limit of 1 per daemon and keep the VM out of public
ingress. Verify access as the runner account with
`docker --host "$CABINET_CI_DOCKER_HOST" info --format '{{json .Labels}}'`.
Set the GitLab artifact size limit to accommodate the five packaged JARs from
`backend`. Verify this on the first pipeline and check that the consumers accept
both checksum files.

Quality jobs run for MRs and protected refs. The longer TLS/restore, storage,
shared state, demo reset and reliability exercises run automatically on protected
refs and schedules. Other pipelines can run these longer jobs manually. The
Compose project labels, local image tags and loopback port leases identify
disposable jobs. Reports intentionally exclude database dumps, configuration
files, token-bearing load cohorts and Playwright network trace archives.
Playwright still publishes its HTML/JUnit results and failure screenshots.

For maintenance, pause the runner in GitLab and wait for its active job to
finish or cancel the job through GitLab so its cleanup has a chance to run.
On the worker, use the matching job ID and quality socket to inspect or clean
only job-owned resources from the checked-out tree.

```bash
export CABINET_CI_DOCKER_HOST=unix:///var/run/docker.sock
export CI_JOB_ID=12345
export CI_JOB_NAME=maintenance
docker --host "$CABINET_CI_DOCKER_HOST" ps -a \
  --filter "label=com.docker.compose.project=cabinet-e2e-$CI_JOB_ID"
bash ci/gitlab/quality/cleanup.sh
```

Normal cleanup also removes the four standalone integration containers named
`cabinet-ci-${CI_JOB_ID}-postgres`, `-clamav`, `-seaweedfs` and `-valkey`. The same maintenance command removes any of those integration containers after an
interrupted job. The production-like
restore test chooses separate random project names and records them in its JSON
report. Use that report to identify any abandoned test volumes before removal, or
replace the drained dedicated VM.

If the official ClamAV CDN rejects FreshClam from a runner, an operator can
temporarily set `CABINET_CI_CLAMAV_SEED_IMAGE` to an official
`clamav/clamav@sha256:…` image containing a verified fresh daily database. Set
`CABINET_CI_CLAMAV_SEED_SHA256` to the digest of its `daily.cvd` and
`CABINET_CI_CLAMAV_SEED_SERIAL` to the matching numeric database version.
The job verifies the digest, signature, version and age before seeding
the pinned scanner runtime. The normal scanner health gate still checks
the loaded database. Unset or refresh these values when the seed expires.

For a signed CVD downloaded by FreshClam over an approved network, put
`daily.cvd` and `daily-<serial>.cvd.sign` in the pre-provisioned read-only
`/var/lib/pump-ci/clamav-official/<serial>` directory on every selected quality
worker and `/var/lib/pump-release/clamav-official/<serial>` on the protected
release worker. Set `CABINET_CI_CLAMAV_SEED_FROM_FILES=1` with the same digest
and serial variables, and unset the image variable. The release job scans and
publishes the verified seeded scanner as an
OCI digest when a seed is configured. A deployment preserves newer signatures
on its persistent scanner volume.

The required Ollama scan runs on the project runner tagged
`cabinet-ollama-cache`. It verifies the official OCI RepoDigest and the
`linux/amd64` platform at the isolated Docker daemon, then scans a local
archive using the same Trivy checks and exceptions. The job retains the JSON
scan results and OCI evidence. The archive is disposable and is removed after
the job.

Trivy fetches fresh vulnerability and Java databases from the official GHCR
repositories first, with the official Google mirror as fallback. Database
updates remain enabled for all scans.
