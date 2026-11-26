# GitLab CI and release

## Runners and canary

Restrict every runner to this project and tagged jobs. Keep `cabinet-ci` isolated
from deployment credentials and existing workloads, with one job per quality
Docker daemon. See [quality runner provisioning](quality/README.md). Install Helm
3.19+ or Helm 4 on quality and deployment runners. The protected release runner
needs the Docker buildx plugin and enough disk for image scanning. The protected
deploy runner needs SSH, Git, Python, kubectl and a route to the new private API.

Once the selected runners are online, start a pipeline on the protected
`feat/final-runner-canary` branch with `CABINET_RUNNER_CANARY=1`. Jobs run in
order. The CI runner builds and uploads a tiny image and emits
`runner-canary-image.txt`. The release runner pulls this exact digest and checks
the OCI revision. The deploy runner checks its local tools. Record the pipeline
URL and digest. Remove the canary variable before starting the quality pipeline.

## Promotion

1. Create a normal pipeline on the protected release ref. Required checks include
   the extended jobs. Click `release-images` after the gates pass. It checks the
   producer hashes, scans the locally built images and publishes
   `release.json` with the revision and Registry digests. Keep the image tags
   and pipeline artifact for the rollback window.
2. Run `dev-deploy`. It streams a Git archive of the revision to the Dev VM and
   applies the immutable release manifest with the Dev host's own runtime
   settings. Only Caddy publishes ports. After HTTPS and jury-role checks pass,
   the job records the manifest hash for promotion.
3. Run `prod-deploy` only after the protected production environment and the
   initial synthetic roles are configured. It verifies the Dev manifest hash
   and the new namespace UID, renders the chart with server dry-run, waits for
   upgrades, checks the Kubernetes revision and matches the public frontend
   bytes to both pods. The jury-role smoke must also pass.

The deployment SSH key is a protected file variable scoped to `dev`. Add its
public key on `pump-final` using
`restrict,command="/usr/local/sbin/cabinet-dev-deploy"`. Pin the verified SSH host
key in a separate protected file variable. Install
[dev-ssh-entrypoint.sh](dev-ssh-entrypoint.sh) as a root-owned executable at that
path. The account `cabinet-deploy` can run Docker on the dedicated Dev VM. Keep
its host environment and Registry read-only login private. See
[the Dev runbook](../../deploy/README.md) for initial setup and recovery.

Set these GitLab variables in their matching protected environment.
File values are indicated explicitly.

- Dev uses `CABINET_DEV_SSH_HOST`, `CABINET_DEV_URL`,
  `CABINET_DEV_SSH_KEY` (file), and `CABINET_DEV_KNOWN_HOSTS` (file).
- Production uses `CABINET_PROD_CONTEXT`, `CABINET_PROD_NAMESPACE`
  (`cabinet-prod`), `CABINET_PROD_NAMESPACE_UID`, `CABINET_PROD_URL`,
  `CABINET_PROD_KUBECONFIG` (file), `CABINET_PROD_VALUES` (file), and
  `CABINET_PROD_JURY_CREDENTIALS` (file).

Use separate deploy job credentials. The Kubernetes Secret and namespace pull
secret are provisioned separately from Helm values, with only a
`read_registry` token in the pull secret. The app gateway stays on ClusterIP.
Ingress uses class `cabinet-public` and the Edge-managed
`cabinet-prod-tls` Secret. Enter the observed controller source and DNS addresses
in the values file. Edge owns NodePorts 30080 and 30443.

On first Prod release, apply the chart with infrastructure only, then run the app
upgrade using the Dev-accepted artifact. In the private operator session, use
the chart's jury provisioner to initialize the synthetic identities. Retry the
Prod job to check the running service. See the [chart runbook](../../helm/cabinet/README.md)
for migration, backup and recovery. Treat Helm rollback and PostgreSQL restore
as separate operations. Verify an isolated restore before any live recovery.

For acceptance retain the Git SHA, artifact manifest hash, pipeline/job URLs,
certificate/HTTPS result, three role results and negative access result.
Keep private settings, credentials and database dumps on their respective hosts.
