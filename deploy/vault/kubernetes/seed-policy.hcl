path "pump/data/prod/kubernetes/cabinet-secrets" {
  capabilities = ["create", "read"]
}

path "pump/data/prod/kubernetes/cabinet-s3-app" {
  capabilities = ["create", "read"]
}

path "pump/data/prod/kubernetes/cabinet-s3-admin" {
  capabilities = ["create", "read"]
}

path "pump/data/prod/kubernetes/cabinet-prod-registry" {
  capabilities = ["create", "read"]
}

path "pump/data/prod/kubernetes/engagement-ollama-ssh" {
  capabilities = ["create", "read"]
}
