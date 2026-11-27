# PUMP Vault host

The host runs **HashiCorp Vault Community Edition 2.2.2** as `pump-vault.service` with a single Integrated Storage (Raft) voter. `dev` can perform approved host administration through `sudo`. The service uses the separate `pump-vault` identity with a non-login shell. A VM, storage or process outage takes Vault offline. After a reboot, the operator unseals it from off-VM Shamir custody before clients can read secrets.

## Installation

Use a dedicated Ubuntu 24.04 VM with a separate persistent filesystem mounted by UUID at `/var/lib/pump-vault`. Disable swap on this VM. Provide a private IPv4 on the VM and a private DNS name reachable by the intended clients. Scope TCP 8200 to the approved private client sources and TCP 22 to approved operators. TCP 8201 binds to loopback for the single Raft voter. Check network policy and host firewall before starting the service.

Install the OS prerequisites on the dedicated VM with `sudo apt-get install ca-certificates curl gnupg iproute2 openssl unzip util-linux`. Have the off-host CA sign a leaf with the exact Vault DNS SAN and server authentication usage. Stage the leaf first in the PEM full chain, any intermediates after it, the unencrypted server key as root with mode `0600`, and the trusted CA bundle. The signing private key stays off the Vault VM. The installer checks the chain, hostname and leaf/key match before installing the signed Vault binary.

```bash
sudo ./deploy/vault/host/install-vault.sh "$PRIVATE_IPV4" "$VAULT_DNS" \
  /root/pump-vault-bootstrap/fullchain.pem /root/pump-vault-bootstrap/key.pem \
  /root/pump-vault-bootstrap/ca.pem
sudo systemctl start pump-vault.service
sudo systemctl is-active pump-vault.service
sudo ss -ltn 'sport = :8200 or sport = :8201'
```

The installer verifies the official archive manifest through an isolated GnuPG keyring using the pinned HashiCorp signing fingerprint, then checks the archive SHA-256 before extracting it. It preserves existing Raft data and enables the unit without starting it. Existing files are kept if their contents and metadata already match. Changed installation files require a controlled stop, a current snapshot and custodians available to unseal after startup. The unit sources the root-only TLS key through systemd credentials and checks the Raft mount at every start. Distribute the client trust bundle at `/etc/pump-vault/ca.pem` through approved configuration delivery. Remove the redundant staged leaf-key copy after the secured backup and service start checks. [Vault release](https://releases.hashicorp.com/vault/2.2.2/) · [Signing fingerprint](https://www.hashicorp.com/en/trust/security).

For a host without direct release download access, pass a sixth argument with the staged official release files. It must contain the release key as `hashicorp-release-key.asc`, `vault_2.2.2_SHA256SUMS`, `vault_2.2.2_SHA256SUMS.72D7468F.sig`, and the Linux archive for this host. Installation applies the same pinned signature and hash checks to the staged files.

## Bootstrap and health

From a trusted operator client, set `VAULT_ADDR=https://<private-dns>:8200` and `VAULT_CACERT` to the approved CA bundle. Check `vault status -format=json` and `vault operator init -status` before initialization. A correctly pinned TLS health endpoint returns `501` before initialization, `503` when sealed, and `200` for the active unsealed voter. Check the private DNS and certificate through the real Dev and Prod client paths before migrating secrets.

Initialize only the verified new, empty cluster. This installation uses 2-of-3 Shamir with owner custody in macOS Keychain outside the VM and CI. See [PUMP custody and bootstrap](../README.md#установка-и-начальные-полномочия). Where approved PGP recipients are already available, Vault also supports encrypting the initialization response for off-VM custody.

```bash
umask 077
vault operator init -key-shares=3 -key-threshold=2 \
  -pgp-keys="$CUSTODIAN_PUBLIC_KEYS" -root-token-pgp-key="$BOOTSTRAP_PUBLIC_KEY" \
  -format=json >"$PRIVATE_INIT_RECEIPT"
```

Distribute encrypted shares directly to their custodians and exercise the documented recovery procedure. Each selected custodian uses the interactive `vault operator unseal` prompt for their own share. Keep decrypted shares and authorization material out of the VM, systemd, CI and logs. Use the temporary root credential in a secured operator session to enable audit and configure approved administrative and service roles. Verify those roles, then revoke the temporary root credential. Keep break-glass recovery independent of Vault and CI.

```bash
vault audit enable -path=pump-file file file_path=/var/log/pump-vault/audit.jsonl mode=0600
vault audit list -format=json
```

Verify a test request produced a new audit event by inspecting the file metadata and count. Keep audit payloads restricted and preserve Vault's default field hashing. Use an operations-owned rotation mechanism with approved retention and protected off-host storage. After rotation, run `sudo systemctl kill --kill-whom=main --signal=SIGHUP pump-vault.service` so the file audit device reopens its destination, then verify new events arrive. Track audit storage capacity since failure of every audit device can block Vault requests. To replace a server certificate, coordinate a controlled restart with the custodians. Systemd copies the file credentials at startup.

## Snapshots and recovery

Use an independently authenticated backup client, separate from application or CI identities. Grant it the snapshot read path only and test that it cannot restore snapshots or read/write application data. Use a short-lived token and the pinned TLS endpoint. The backup service should run daily and after material policy or credential changes, and publish only status, timestamps and checksum verification.

```hcl
path "sys/storage/raft/snapshot" {
  capabilities = ["read"]
}
```

In the backup client's private spool, `vault operator raft snapshot save "$SNAPSHOT_PATH"` writes the snapshot. Check it is nonempty, run `vault operator raft snapshot inspect "$SNAPSHOT_PATH"` with details restricted, and store a SHA-256 sidecar. Transfer snapshots and audit archives to separate, encrypted, off-host storage with independently managed access and retention. Maintain an independent record of snapshot index, timestamp, Vault version and cluster identity. Pick the newest verified compatible snapshot and record which subsequent changes would be lost. Obtain authorization before a recovery that would discard writes.

Exercise restore first on an isolated Vault installation with the same pinned binary and a new dedicated disk. Block existing workloads from the recovery endpoint and isolate egress from production integrations. Use the documented Shamir disaster recovery procedure with the original custodians available. A restore on the original cluster uses the regular restore API. A new isolated cluster requires the documented `-force` form when the bootstrap key differs from the original shares. Wait for restore completion in Vault's service status and logs, unseal with the original shares as needed, and check the audit device, auth mounts, role denials and a dedicated canary. Reconcile device copies and any policy, token or secret changes after the snapshot before routing clients to the recovered cluster. Keep current data and rollback material until proof is recorded.

See [Raft deployment](https://developer.hashicorp.com/vault/tutorials/day-one-raft/raft-deployment-guide), [audit file devices](https://developer.hashicorp.com/vault/docs/audit/file), [snapshot saving](https://developer.hashicorp.com/vault/docs/sysadmin/snapshots/save) and [disaster recovery](https://developer.hashicorp.com/validated-designs/vault/administration-guide/backup-and-restore-operations).
