#!/bin/sh
set -eu
umask 077

# Kubelet projects the Secret as root. Copy into a sidecar-only tmpfs as the
# nonroot SSH user so OpenSSH sees a private 0600 file owned by that user.
cp /run/ssh-source/id_ed25519 /run/bridge/id_ed25519
cp /run/ssh-source/known_hosts /run/bridge/known_hosts
chmod 0600 /run/bridge/id_ed25519 /run/bridge/known_hosts

child=
stop() {
  if [ -n "$child" ]; then
    kill "$child" 2>/dev/null || true
    wait "$child" 2>/dev/null || true
  fi
  exit 0
}
trap stop TERM INT

# A broken route or model service does not affect the pod's readiness.
while :; do
  ssh -4 -F /dev/null -NT -p "$SSH_PORT" -i /run/bridge/id_ed25519 \
    -o BatchMode=yes -o IdentitiesOnly=yes -o IdentityAgent=none \
    -o ExitOnForwardFailure=yes -o StrictHostKeyChecking=yes \
    -o HostKeyAlgorithms=ssh-ed25519 -o HostKeyAlias=gpu-vm3-pump-model \
    -o UserKnownHostsFile=/run/bridge/known_hosts -o GlobalKnownHostsFile=/dev/null \
    -o UpdateHostKeys=no -o ConnectTimeout=6 \
    -o ServerAliveInterval=15 -o ServerAliveCountMax=3 \
    -L 127.0.0.1:11434:127.0.0.1:11434 "pump-model-forward@$SSH_HOST" &
  child=$!
  wait "$child" || true
  sleep 5 &
  child=$!
  wait "$child" || true
done
