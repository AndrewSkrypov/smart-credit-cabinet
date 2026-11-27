#!/usr/bin/env bash
set -euo pipefail

: "${CI_COMMIT_SHA:?}" "${CABINET_PROD_KUBECONFIG:?}" "${CABINET_PROD_CONTEXT:?}" "${CABINET_PROD_NAMESPACE:?}" "${CABINET_PROD_NAMESPACE_UID:?}" "${CABINET_PROD_VALUES:?}" "${CABINET_PROD_URL:?}" "${CABINET_PROD_JURY_CREDENTIALS:?}"
export KUBECONFIG="$CABINET_PROD_KUBECONFIG"
export HELM_DRIVER=configmap
release=cabinet
namespace="$CABINET_PROD_NAMESPACE"
context="$CABINET_PROD_CONTEXT"
kubectl_args=(--context "$context" --namespace "$namespace")
helm_args=(--kube-context "$context" --namespace "$namespace")
scratch=$(mktemp -d)
opening_marker=cabinet-catalog-gateway-opening
opening=false
cleanup() {
    local status=$?
    if [[ "$opening" == true && "$status" != 0 ]]; then close_gateway || true; fi
    rm -rf -- "$scratch"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# The namespace UID is recorded independently on the newly provisioned cluster.
actual_uid=$(kubectl "${kubectl_args[@]}" get namespace "$namespace" -o jsonpath='{.metadata.uid}')
[[ "$actual_uid" == "$CABINET_PROD_NAMESPACE_UID" ]]

selector='app.kubernetes.io/instance=cabinet,app.kubernetes.io/name'
gateway_closed() {
    local replicas pods
    replicas=$(kubectl "${kubectl_args[@]}" get deployment gateway -o jsonpath='{.spec.replicas}') || return 1
    pods=$(kubectl "${kubectl_args[@]}" get pods -l "$selector=gateway" -o name) || return 1
    [[ "$replicas" == 0 && -z "$pods" ]]
}

wait_gateway_closed() {
    local attempt
    for ((attempt = 0; attempt < 40; attempt++)); do
        if gateway_closed; then return 0; fi
        sleep 5
    done
    gateway_closed
}

close_gateway() {
    if gateway_closed; then return 0; fi
    if ! kubectl "${kubectl_args[@]}" scale deployment/gateway --replicas=0 || ! wait_gateway_closed; then
        echo 'Failed to close the gateway; close it from the production operator context.' >&2
        return 1
    fi
}

kubectl "${kubectl_args[@]}" get configmap "$opening_marker" --ignore-not-found -o json >"$scratch/opening.json"
# Recover the traffic barrier before Helm can reject an unfinished upgrade.
if [[ -s "$scratch/opening.json" ]]; then
    close_gateway
    opening=true
fi

test -r "$CABINET_PROD_JURY_CREDENTIALS"
python3 ci/gitlab/release-manifest.py release.json "$CI_COMMIT_SHA" "$CI_REGISTRY_IMAGE" --helm-values "$scratch/release.json"
values=(-f "$CABINET_PROD_VALUES" -f "$scratch/release.json")
helm lint helm/cabinet "${values[@]}"
helm template "$release" helm/cabinet --namespace "$namespace" --is-upgrade "${values[@]}" --set app.enabled=true \
    --show-only templates/catalog-release-state.yaml | sed -n 's/^    \({.*}\)$/\1/p' >"$scratch/desired.json"
helm list "${helm_args[@]}" --all --filter '^cabinet$' -o json >"$scratch/installed.json"
status=$(python3 - "$scratch/installed.json" <<'PY'
from pathlib import Path
import json
import sys

installed = json.loads(Path(sys.argv[1]).read_text())
if len(installed) > 1:
    raise SystemExit("Ambiguous cabinet Helm release")
print(installed[0]["status"] if installed else "missing")
PY
)
read_writer_flags() {
    local flags
    flags=$(kubectl "${kubectl_args[@]}" exec -i postgres-0 -c postgres -- sh -s <ci/gitlab/catalog-prod-owner.sh)
    mapfile -t owners <<< "$flags"
    [[ ${#owners[@]} == 2 ]]
}

verify_writer_history() {
    {
        printf '%s\n' 'snapshot_export() {' "    cat <<'CATALOG_EXPORT_SQL'"
        cat services/catalog-grpc/cutover/export.sql
        printf '%s\n' 'CATALOG_EXPORT_SQL' '}'
        cat ci/gitlab/catalog-prod-owner.sh
    } | kubectl "${kubectl_args[@]}" exec -i postgres-0 -c postgres -- sh -s -- history
}

if [[ "$status" == missing ]]; then
    printf '{}\n' >"$scratch/current.json"
    owners=(absent absent)
else
    helm get values "$release" "${helm_args[@]}" --all -o json >"$scratch/current.json"
    read_writer_flags
fi
decision=$(python3 ci/gitlab/catalog_prod_guard.py "$scratch/current.json" "$scratch/desired.json" "$status" \
    "${owners[@]}" --opening "$scratch/opening.json")
read -r strategy catalog_mode catalog_enabled <<< "$decision"

verify_args=(release.json "$CABINET_PROD_URL" --context "$context" --namespace "$namespace")
if [[ "$catalog_enabled" == true ]]; then
    python3 ci/gitlab/release-manifest.py release.json "$CI_COMMIT_SHA" "$CI_REGISTRY_IMAGE" --require-catalog
    verify_args+=(--catalog-mode "$catalog_mode")
fi
case "$strategy" in
    switch)
        gateway_closed || { echo 'Catalog maintenance requires a stopped gateway' >&2; exit 1; }
        for writer in credit catalog-grpc; do
            replicas=$(kubectl "${kubectl_args[@]}" get deployment "$writer" -o jsonpath='{.spec.replicas}')
            [[ "$replicas" == 0 ]] || { echo 'Scale Credit and Catalog to zero before changing the writer' >&2; exit 1; }
        done
        pods=$(kubectl "${kubectl_args[@]}" get pods -l "$selector in (credit,catalog-grpc)" -o name)
        [[ -z "$pods" ]] || { echo 'Drain Credit and Catalog before changing the writer' >&2; exit 1; }
        verify_writer_history
        ;;
    open)
        for verb in get patch update; do
            kubectl "${kubectl_args[@]}" auth can-i "$verb" deployment/gateway --subresource=scale --quiet ||
                { echo 'Apply the gateway scale permissions from prod-rbac.yaml before opening' >&2; exit 1; }
        done
        gateway_closed || { echo 'Verify that the Catalog maintenance gateway is closed' >&2; exit 1; }
        python3 ci/gitlab/verify-prod.py "${verify_args[@]}" --internal-only
        ;;
esac

apply_args=()
case "$(helm version --template '{{.Version}}')" in
    v3.*) automatic_failure_flag=--atomic;;
    v4.*)
        automatic_failure_flag=--rollback-on-failure
        # Operators and recovery can use the scale subresource during maintenance.
        # Client-side merge restores replicas without taking over SSA field ownership.
        case "$strategy" in hold|switch|open) apply_args+=(--server-side=false);; esac
        ;;
    *) echo "Expected Helm 3 or Helm 4" >&2; exit 2;;
esac

if [[ "$status" == missing ]]; then
    helm install "$release" helm/cabinet "${helm_args[@]}" "${values[@]}" "${apply_args[@]}" --set app.enabled=false --dry-run=server --hide-secret >"$scratch/bootstrap-dry-run.log"
    helm install "$release" helm/cabinet "${helm_args[@]}" "${values[@]}" "${apply_args[@]}" --set app.enabled=false --wait --timeout 15m
    # A new Helm release can attach to previously initialized storage.
    read_writer_flags
    confirmed=$(python3 ci/gitlab/catalog_prod_guard.py "$scratch/current.json" "$scratch/desired.json" "$status" \
        "${owners[@]}" --opening "$scratch/opening.json")
    [[ "$confirmed" == "$decision" ]]
fi
helm upgrade "$release" helm/cabinet "${helm_args[@]}" "${values[@]}" "${apply_args[@]}" --set app.enabled=true --dry-run=server --hide-secret >"$scratch/app-dry-run.log"
if [[ "$strategy" == open && "$opening" == false ]]; then
    kubectl "${kubectl_args[@]}" create configmap "$opening_marker" \
        --from-literal="revision=$CI_COMMIT_SHA" --from-literal="mode=$catalog_mode" --from-literal="enabled=$catalog_enabled"
    opening=true
fi
failure_flag=--wait
if [[ "$strategy" == automatic ]]; then
    failure_flag=$automatic_failure_flag
fi
if ! helm upgrade "$release" helm/cabinet "${helm_args[@]}" "${values[@]}" "${apply_args[@]}" --set app.enabled=true "$failure_flag" --timeout 25m; then
    if [[ "$strategy" == switch ]]; then
        echo 'Catalog maintenance remains closed. Verify or reverse the database transfer before restoring the prior Credit mode.' >&2
    fi
    exit 1
fi
if [[ "$strategy" == hold || "$strategy" == switch ]]; then
    wait_gateway_closed || { echo 'Catalog maintenance gateway did not drain' >&2; exit 1; }
    python3 ci/gitlab/verify-prod.py "${verify_args[@]}" --internal-only
    if [[ -s "$scratch/opening.json" ]]; then
        kubectl "${kubectl_args[@]}" delete configmap "$opening_marker"
    fi
    echo 'Catalog maintenance retained. Public acceptance remains pending.'
    exit 0
fi
if ! python3 ci/gitlab/verify-prod.py "${verify_args[@]}" ||
    ! python3 deploy/verify-jury.py --url "$CABINET_PROD_URL" --credentials "$CABINET_PROD_JURY_CREDENTIALS"; then
    exit 1
fi
if [[ "$opening" == true ]]; then
    opening=false
    kubectl "${kubectl_args[@]}" delete configmap "$opening_marker"
fi
