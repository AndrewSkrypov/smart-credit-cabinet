# Production ingress controller

Use Traefik chart 42.4.3 and Kubernetes Ingress resources with the
`cabinet-public` class in `cabinet-prod` and `monitoring`. The application
Ingress references `cabinet-prod-tls` in `cabinet-prod`, and the Grafana
Ingress references `grafana-prod-tls` in `monitoring`. Configure the cert-manager HTTP-01 solver
with `ingressClassName: cabinet-public`. The solver route takes precedence over
the HTTP-to-HTTPS redirect.

Render locally:

```sh
helm template cabinet-edge traefik --repo https://traefik.github.io/charts \
  --version 42.4.3 --namespace cabinet-prod --skip-crds -f helm/edge/values.yaml
```

Install from an authenticated context for the target cluster. Create the
`monitoring` namespace before installing or upgrading the chart. The chart
installs the IngressClass and controller RBAC, so use the cluster bootstrap
identity. The controller reads Secrets in `cabinet-prod` and `monitoring`.

```sh
helm upgrade --install cabinet-edge traefik --repo https://traefik.github.io/charts \
  --version 42.4.3 --namespace cabinet-prod --create-namespace --skip-crds \
  -f helm/edge/values.yaml --wait --timeout 5m
kubectl -n cabinet-prod rollout status daemonset/cabinet-edge-traefik --timeout=5m
kubectl -n cabinet-prod get pods -l app.kubernetes.io/instance=cabinet-edge-cabinet-prod -o wide
kubectl -n cabinet-prod get endpointslice -l kubernetes.io/service-name=cabinet-edge-traefik
```

Add only worker private addresses with ready local Traefik endpoints to the T1
TCP pools. Map public TCP 80 to 30080 and 443 to 30443, with TCP health checks
on the corresponding backend ports. Confirm the actual T1 source addresses
and restrict worker access with the worker security group. TLS passes through
the LB and terminates at Traefik. Verify client source IP before enabling any
source-address-dependent application policy. Keep forwarded-header and PROXY
protocol trust disabled until the applicable source and policy are verified.

After binding both listeners, check the redirect and the certificate for both
published production hostnames:

```sh
for name in credit-cabinet.ru www.credit-cabinet.ru; do
  curl --fail --silent --show-error --head "http://${name}/"
  curl --fail --silent --show-error --head "https://${name}/"
done
kubectl -n cabinet-prod get certificate
```

## Certificate issuance

Bootstrap cert-manager as a separate Helm release. Check that the cluster does
not already have a cert-manager installation before installing it.

```sh
helm upgrade --install cert-manager oci://quay.io/jetstack/charts/cert-manager \
  --version v1.21.3 --namespace cert-manager --create-namespace \
  -f helm/edge/cert-manager-values.yaml \
  --wait --timeout 5m
```

Apply the namespaced Issuer and Certificate only after both public names
resolve to the intended LB and port 80 reaches Traefik. The application
Ingress can reference the Secret directly. Issuance and renewal belong to
the explicit Certificate resource.

```sh
kubectl apply -f helm/edge/acme/production.yaml
kubectl -n cabinet-prod wait certificate/cabinet-prod --for=condition=Ready --timeout=5m
kubectl -n cabinet-prod get certificate cabinet-prod \
  -o 'custom-columns=NAME:.metadata.name,EXPIRES:.status.notAfter,RENEWAL:.status.renewalTime'
```

When `grafana.credit-cabinet.ru` resolves to the same LB and port 80 reaches
Traefik, apply the separate Grafana Issuer and Certificate:

```sh
kubectl apply -f helm/edge/acme/grafana.yaml
kubectl -n monitoring wait certificate/grafana-prod --for=condition=Ready --timeout=5m
kubectl -n monitoring get certificate grafana-prod \
  -o 'custom-columns=NAME:.metadata.name,EXPIRES:.status.notAfter,RENEWAL:.status.renewalTime'
curl --silent --show-error --head https://grafana.credit-cabinet.ru/
```

If a temporary, publicly trusted DNS-01 certificate already populates
`cabinet-prod-tls`, leave the Secret in place while bringing up the HTTP-01
path. A manual DNS-01 certificate has no automated renewal. After applying
the Issuer and Certificate, verify a successful cert-manager
`CertificateRequest` and a populated renewal time. If the existing certificate
continues to be served, check the presented certificate serial and the
CertificateRequest status before declaring the handover complete.
