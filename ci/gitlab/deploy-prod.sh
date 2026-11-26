#!/usr/bin/env bash
set -euo pipefail

: "${CI_COMMIT_SHA:?}" "${CABINET_PROD_KUBECONFIG:?}" "${CABINET_PROD_CONTEXT:?}" "${CABINET_PROD_NAMESPACE:?}" "${CABINET_PROD_NAMESPACE_UID:?}" "${CABINET_PROD_VALUES:?}" "${CABINET_PROD_URL:?}" "${CABINET_PROD_JURY_CREDENTIALS:?}"
test -r "$CABINET_PROD_JURY_CREDENTIALS"
export KUBECONFIG="$CABINET_PROD_KUBECONFIG"
export HELM_DRIVER=configmap
release=cabinet
namespace="$CABINET_PROD_NAMESPACE"
context="$CABINET_PROD_CONTEXT"
kubectl_args=(--context "$context" --namespace "$namespace")
helm_args=(--kube-context "$context" --namespace "$namespace")
scratch=$(mktemp -d)
trap 'rm -rf -- "$scratch"' EXIT
python3 ci/gitlab/release-manifest.py release.json "$CI_COMMIT_SHA" "$CI_REGISTRY_IMAGE" --helm-values "$scratch/release.json"

# The namespace UID is recorded independently on the newly provisioned cluster.
actual_uid=$(kubectl "${kubectl_args[@]}" get namespace "$namespace" -o jsonpath='{.metadata.uid}')
[[ "$actual_uid" == "$CABINET_PROD_NAMESPACE_UID" ]]
values=(-f "$CABINET_PROD_VALUES" -f "$scratch/release.json")
helm lint helm/cabinet "${values[@]}"
helm list "${helm_args[@]}" --all --filter '^cabinet$' -o json >"$scratch/installed.json"
if python3 -c 'import json, sys; sys.exit(bool(json.load(open(sys.argv[1]))))' "$scratch/installed.json"; then
    helm install "$release" helm/cabinet "${helm_args[@]}" "${values[@]}" --set app.enabled=false --dry-run=server --hide-secret >"$scratch/bootstrap-dry-run.log"
    helm install "$release" helm/cabinet "${helm_args[@]}" "${values[@]}" --set app.enabled=false --wait --timeout 15m
fi
helm upgrade "$release" helm/cabinet "${helm_args[@]}" "${values[@]}" --set app.enabled=true --dry-run=server --hide-secret >"$scratch/app-dry-run.log"
case "$(helm version --template '{{.Version}}')" in
    v3.*) failure_flag=--atomic;;
    v4.*) failure_flag=--rollback-on-failure;;
    *) echo "Expected Helm 3 or Helm 4" >&2; exit 2;;
esac
helm upgrade "$release" helm/cabinet "${helm_args[@]}" "${values[@]}" --set app.enabled=true "$failure_flag" --timeout 25m
python3 ci/gitlab/verify-prod.py release.json "$CABINET_PROD_URL" --context "$context" --namespace "$namespace"
python3 deploy/verify-jury.py --url "$CABINET_PROD_URL" --credentials "$CABINET_PROD_JURY_CREDENTIALS"
