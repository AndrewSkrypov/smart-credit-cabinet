# Vault deployment handoff

GitLab CE uses `id_tokens` and the Vault JWT API. The reviewed CI change can include `job-identity.yml` and extend the identity template in `dev-deploy` and `prod-deploy`. The environment-scoped `PUMP_VAULT_AUDIENCE` contains the verified Vault HTTPS origin. The jobs keep the protected release ref, dedicated tags, resource groups and artifact dependencies. Prod verifies the Dev attestation before its handoff. [GitLab ID tokens](https://docs.gitlab.com/ci/secrets/id_token_authentication/)

The runner operator installs a root-owned `/etc/pump-vault-ci/client.json` with a single `address` field and a root-owned `/etc/pump-vault-ci/ca.pem` with the verified CA. The files and directory must be readable by the dedicated runner account and writable only by root. The job audience and the configured HTTPS origin must match exactly, with no trailing slash. The wrapper validates deployment claims before contacting Vault. Vault verifies the signature and authorizes the login using GitLab's OIDC discovery at `https://gitlab.credit-cabinet.ru`.

Use auth mount `pump-gitlab` and the KV v2 mount `pump`. Each role has the matching read policy in this directory, a service token with an explicit maximum TTL of 180 seconds, and no default policy. Bind the exact audience and issuer and match these claims without globs.

- Both roles bind `job_project_id="1"`, `ref="feat/final-platform-release"`, `ref_path="refs/heads/feat/final-platform-release"`, `ref_protected="true"`, `environment_action="start"`, and `pipeline_source` in `push`, `api`, `web`.
- `pump-ci-dev` binds `environment="dev"` and numeric `runner_id=2`. Its exact record holds `CABINET_DEV_SSH_KEY` and `CABINET_DEV_KNOWN_HOSTS`.
- `pump-ci-prod` binds `environment="production"` and numeric `runner_id=4`. Its exact record holds `CABINET_PROD_VALUES`. Jury credentials and just-in-time kubeconfig keep their existing protected environment file scopes for the first migration.

`job_id` is a per-run audit identifier. GitLab 19.6.2 does not emit a stable job name claim. CE permits both deployment jobs to users with merge access on this shared branch. Vault role bindings separate the two job identities and secret paths. Any later requirement to give people Dev-only deployment rights also needs a separate authorization control. [Vault JWT auth](https://developer.hashicorp.com/vault/docs/auth/jwt) · [GitLab job controls](https://docs.gitlab.com/ci/jobs/job_control/)

Use the wrapper at the existing script call, after staging any files already required by that job.

```sh
python3 ci/gitlab/vault/with-deploy-secrets.py dev -- bash ci/gitlab/deploy-dev.sh
python3 ci/gitlab/vault/with-deploy-secrets.py production -- bash ci/gitlab/deploy-prod.sh
```

The wrapper passes only the allowlisted file paths to the deployment child and keeps the GitLab JWT and Vault token out of that child's environment. Existing scalar connection settings, release inputs and attestations use the current paths. Run with job debug tracing disabled.

An authenticated operator seeds only the allowlisted fields through protected input streams to Vault. Use a short-lived writer scoped to the exact destination records. Keep source values, tokens and rendered secret files out of arguments, logs, artifacts and the worktree. Record the Vault versions and verify the exact imported bytes in memory before the canary. Record status only for the canary JWT checks and deny cross-role login and cross-environment reads. Confirm each real deployment consumed its Vault record and preserved release digests before removing corresponding GitLab copies. If a deployment fails, retain the legacy file variables and restore the previous reviewed job invocation while investigating.
