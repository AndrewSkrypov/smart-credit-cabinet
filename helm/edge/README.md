# Production ingress controller

Use Traefik chart 42.4.3 and Kubernetes Ingress resources with the
`cabinet-public` class in `cabinet-prod`. The application Ingress references
`cabinet-prod-tls` in that namespace. Configure the cert-manager HTTP-01 solver
with `ingressClassName: cabinet-public`. The solver route takes precedence over
the HTTP-to-HTTPS redirect.

Render locally:

```sh
helm template cabinet-edge traefik --repo https://traefik.github.io/charts \
  --version 42.4.3 --namespace cabinet-prod --skip-crds -f helm/edge/values.yaml
```

Install from an authenticated context for the target cluster. The chart
installs the IngressClass and chart RBAC, so use the cluster bootstrap identity.

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
