{{- define "cabinet.mcp.identityOrigin" -}}
https://cabinet-mcp-identity:8444
{{- end -}}

{{- define "cabinet.mcp.identitySidecar" -}}
- name: mcp-identity-tls
  image: {{ include "cabinet.image" (list . "gateway") | quote }}
  command: ["nginx", "-c", "/etc/cabinet-mcp/nginx.conf", "-g", "daemon off;"]
  ports:
    - {name: mcp-tls, containerPort: 8444}
  securityContext:
    runAsNonRoot: true
    runAsUser: 10001
    runAsGroup: 10001
    allowPrivilegeEscalation: false
    readOnlyRootFilesystem: true
    capabilities: {drop: [ALL]}
  readinessProbe:
    tcpSocket: {port: mcp-tls}
    timeoutSeconds: 2
  resources:
    requests: {cpu: 20m, memory: 16Mi}
    limits: {cpu: 200m, memory: 48Mi}
  volumeMounts:
    - {name: mcp-identity-config, mountPath: /etc/cabinet-mcp, readOnly: true}
    - {name: mcp-identity-server, mountPath: /run/mcp-identity-tls, readOnly: true}
    - {name: mcp-identity-tmp, mountPath: /tmp}
{{- end -}}
