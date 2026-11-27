{{- define "cabinet.ollamaBridge.container" -}}
{{- $bridge := .Values.ollamaBridge -}}
- name: ollama-ssh-bridge
  image: {{ include "cabinet.image" (list . "ssh-bridge") | quote }}
  command: [/bin/sh, -ec]
  args:
    - |
      {{- .Files.Get "files/ollama-ssh-bridge.sh" | nindent 6 }}
  env:
    - {name: SSH_HOST, value: {{ $bridge.host | quote }}}
    - {name: SSH_PORT, value: {{ int $bridge.port | quote }}}
  securityContext:
    runAsNonRoot: true
    runAsUser: 10002
    runAsGroup: 10002
    allowPrivilegeEscalation: false
    readOnlyRootFilesystem: true
    capabilities: {drop: [ALL]}
    seccompProfile: {type: RuntimeDefault}
  resources:
    {{- toYaml $bridge.resources | nindent 4 }}
  volumeMounts:
    - {name: ollama-ssh-source, mountPath: /run/ssh-source, readOnly: true}
    - {name: ollama-ssh-runtime, mountPath: /run/bridge}
{{- end -}}

{{- define "cabinet.ollamaBridge.volumes" -}}
- name: ollama-ssh-source
  secret:
    secretName: {{ required "ollamaBridge.secretName is required" .Values.ollamaBridge.secretName | quote }}
    defaultMode: 288 # 0440; permitted to the sidecar fsGroup
    items:
      - {key: id_ed25519, path: id_ed25519}
      - {key: known_hosts, path: known_hosts}
- name: ollama-ssh-runtime
  emptyDir: {medium: Memory, sizeLimit: 1Mi}
{{- end -}}
