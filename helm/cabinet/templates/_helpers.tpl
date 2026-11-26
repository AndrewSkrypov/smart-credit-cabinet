{{- define "cabinet.image" -}}
{{- $value := index (index . 0).Values.images (index . 1) -}}
{{- if not (regexMatch "^[^[:space:]@]+@sha256:[a-f0-9]{64}$" ($value | default "")) -}}
{{- fail (printf "images.%s must be an immutable image@sha256 reference" (index . 1)) -}}
{{- end -}}
{{- $value -}}
{{- end -}}
{{- define "cabinet.selector" -}}
app.kubernetes.io/instance: {{ (index . 0).Release.Name | quote }}
app.kubernetes.io/name: {{ index . 1 | quote }}
{{- end -}}
{{- define "cabinet.pod" -}}
automountServiceAccountToken: false
{{- with .Values.imagePullSecrets }}
imagePullSecrets:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- end -}}
{{- define "cabinet.secret" -}}
{{- include "cabinet.secretFrom" (list (index . 0).Values.secrets.existingSecret (index . 1) (index . 2)) -}}
{{- end -}}
{{- define "cabinet.secretFrom" -}}
- name: {{ index . 1 }}
  valueFrom:
    secretKeyRef:
      name: {{ index . 0 | quote }}
      key: {{ index . 2 }}
{{- end -}}
{{- define "cabinet.storageClass" -}}
{{- with .Values.persistence.storageClass }}
storageClassName: {{ . | quote }}
{{- end }}
{{- end -}}
{{- define "cabinet.db-env" -}}
- {name: DB_HOST, value: postgres}
- {name: DB_PORT, value: "5432"}
- {name: DB_NAME, value: {{ index . 0 | quote }}}
- {name: DB_USER, value: {{ printf "%s_%s" (index . 0) (index . 1) | quote }}}
{{- end -}}
