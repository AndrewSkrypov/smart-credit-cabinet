# Private production-profile Analytics smoke

`projection-smoke.py` checks the existing production Compose stack in a synthetic
CI project after `build-analytics.sh`. It requires the runner-provided
`CABINET_CI_DOCKER_HOST`, a Unix socket, the engine label `cabinet-ci=true`, and a
numeric `CI_JOB_ID`. It never resolves a default Docker context or uses Dev.

Analytics keeps `alpha-stream`, `alpha-store`, and `alpha-read` and uses the
internal `alpha-cache` network to reach Valkey. Only Analytics and Valkey join
`alpha-cache`; Analytics does not join `cabinet`. Neither dependency nor
Analytics publishes a port. Public Analytics reads remain disabled in Credit.

The smoke validates the JAR checksum and source artifact, builds the actual
prebuilt Analytics image and the pinned patched Kafka recipe, starts Analytics
with `prod`, and requires a matching SQL receipt and projection for a synthetic
Kafka event. The JSON receipt binds source commit/tree, JAR SHA-256, image IDs,
OCI revisions, and the synthetic event IDs.

Kafka selection first checks the local official and mirror references for the
exact digest and `linux/amd64`. If neither is usable, it pulls the same pinned
digest from `mirror.gcr.io` and then the official registry. No tag-only fallback
is allowed. The receipt records both the canonical pin and the selected ref;
the patched image's base label must match that selected ref and canonical digest.

The Valkey assertion uses the actual running Analytics container's settings and
its network namespace. An ephemeral CLI container authenticates with those
settings, requires `PONG`, and also requires unauthenticated access to be denied.
This is a namespace probe, not evidence that a JVM client issued a Valkey call.
Passwords stay in process memory and environments; they are never
command arguments, logs, or receipt fields. No raw container logs are published.

The project is deterministically `cabinet-alpha-projection-$CI_JOB_ID`.
The production generation's named volume is overridden with a job-owned name.
Normal exit, exceptions, SIGTERM and SIGINT enter scoped cleanup. GitLab repeats
that cleanup in `after_script` for interrupted processes, first sourcing the
runner guard again to reset the original shell's Docker context and endpoint.
The 30-minute job reserves 25 minutes for the script and 4 for `after_script`,
leaving a minute for finalization. Cleanup verifies exact
project labels and IDs; images additionally require the job label. It removes
no shared base images and never uses a prune operation.

Lightweight checks, without Docker or a JVM:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -B ci/alpha/test_projection_smoke.py
PYTHONDONTWRITEBYTECODE=1 python3 -B ci/alpha/test_dev_rollout.py
```

Existing Analytics volumes receive only the missing `template0` PUBLIC CONNECT
revocation before migration/runtime startup. This source fix does not change
the immutable pending manifest or perform incident recovery. Source checks and
synthetic receipts do not establish the cause of a particular Dev Compose
failure; that requires a separate Dev diagnostic receipt.
