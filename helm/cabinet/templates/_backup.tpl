{{- define "cabinet.backup-job" -}}
{{- $root := index . 0 }}
{{- $engine := index . 1 }}
backoffLimit: 1
activeDeadlineSeconds: 900
ttlSecondsAfterFinished: 86400
template:
  metadata:
    labels:
      {{- include "cabinet.selector" (list $root "db-maintenance") | nindent 6 }}
  spec:
    {{- include "cabinet.pod" $root | nindent 4 }}
    restartPolicy: Never
    containers:
      - name: backup
        image: {{ include "cabinet.image" (list $root $engine) | quote }}
        env:
          {{- if eq $engine "postgres" }}
          - {name: PGHOST, value: postgres}
          - {name: PGUSER, value: cabinet}
          - {name: PGDATABASE, value: cabinet}
          {{- include "cabinet.secret" (list $root "PGPASSWORD" "POSTGRES_PASSWORD") | nindent 10 }}
          {{- else }}
          {{- include "cabinet.secret" (list $root "VALKEYCLI_AUTH" "VALKEY_PASSWORD") | nindent 10 }}
          {{- end }}
          - {name: BACKUP_RETENTION_DAYS, value: {{ int $root.Values.backup.retentionDays | quote }}}
        command: [/bin/sh, -ec]
        args:
          - |
            {{- $root.Files.Get (printf "files/backup-%s.sh" $engine) | nindent 12 }}
        resources:
          requests: {cpu: 75m, memory: 96Mi}
          limits: {cpu: "1", memory: 384Mi}
        volumeMounts:
          - {name: archive, mountPath: /archive}
    volumes:
      - name: archive
        persistentVolumeClaim:
          claimName: cabinet-{{ $engine }}-backup
{{- end }}
