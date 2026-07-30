#!/usr/bin/env bash
# Genesis AI — full Azure deployment
# Run from repo root: bash deploy/deploy.sh
set -euo pipefail

# ── Configuration ─────────────────────────────────────────────────────────────
RESOURCE_GROUP="genesis-ai-rg"
LOCATION="uksouth"
ACR_NAME="genesisai$(cat /dev/urandom | LC_ALL=C tr -dc 'a-z0-9' | head -c 6)"
ENVIRONMENT="genesis-ai-env"
QDRANT_APP="genesis-qdrant"
APP_NAME="genesis-ai"
STORAGE_ACCOUNT="genesisai$(cat /dev/urandom | LC_ALL=C tr -dc 'a-z0-9' | head -c 8)"
KNOWLEDGE_CONTAINER="knowledge"
QDRANT_API_KEY_VALUE="$(cat /dev/urandom | LC_ALL=C tr -dc 'a-zA-Z0-9' | head -c 32)"

# ── Load .env ─────────────────────────────────────────────────────────────────
if [ ! -f .env ]; then echo "ERROR: .env not found. Run from repo root."; exit 1; fi
set -o allexport; source .env; set +o allexport

for var in ANTHROPIC_API_KEY OPENAI_API_KEY GENESIS_API_KEY; do
  [ -z "${!var:-}" ] && echo "ERROR: $var not set in .env" && exit 1
done

echo ""
echo "╔══════════════════════════════════════╗"
echo "║        Genesis AI — Deploy           ║"
echo "╚══════════════════════════════════════╝"
echo ""
echo "Subscription : $(az account show --query name -o tsv)"
echo "Resource group: $RESOURCE_GROUP ($LOCATION)"
echo ""

# ── 1. Resource group ─────────────────────────────────────────────────────────
echo "[ 1/8 ] Creating resource group..."
az group create -n "$RESOURCE_GROUP" -l "$LOCATION" -o none

# ── 2. Container Registry ─────────────────────────────────────────────────────
echo "[ 2/8 ] Creating container registry: $ACR_NAME..."
az acr create -n "$ACR_NAME" -g "$RESOURCE_GROUP" --sku Basic --admin-enabled true -o none
ACR_SERVER=$(az acr show -n "$ACR_NAME" --query loginServer -o tsv)
ACR_USER=$(az acr credential show -n "$ACR_NAME" --query username -o tsv)
ACR_PASS=$(az acr credential show -n "$ACR_NAME" --query "passwords[0].value" -o tsv)

# ── 3. Build image in ACR (AMD64, in the cloud) ───────────────────────────────
echo "[ 3/8 ] Building genesis-ai image in ACR (this takes ~3 min)..."
az acr build -r "$ACR_NAME" -t genesis-ai:latest . -o none

# ── 4. Container Apps Environment ─────────────────────────────────────────────
echo "[ 4/8 ] Creating Container Apps environment..."
az containerapp env create -n "$ENVIRONMENT" -g "$RESOURCE_GROUP" -l "$LOCATION" -o none

# ── 5. Deploy Qdrant ──────────────────────────────────────────────────────────
echo "[ 5/8 ] Deploying Qdrant..."
az containerapp create \
  -n "$QDRANT_APP" -g "$RESOURCE_GROUP" \
  --environment "$ENVIRONMENT" \
  --image qdrant/qdrant:v1.13.6 \
  --target-port 6333 \
  --ingress external \
  --transport http \
  --min-replicas 1 --max-replicas 1 \
  --cpu 0.5 --memory 1.0Gi \
  --env-vars "QDRANT__SERVICE__API_KEY=$QDRANT_API_KEY_VALUE" \
  -o none

QDRANT_FQDN=$(az containerapp show -n "$QDRANT_APP" -g "$RESOURCE_GROUP" \
  --query properties.configuration.ingress.fqdn -o tsv)
QDRANT_REST_URL="https://$QDRANT_FQDN"

echo "   Qdrant: $QDRANT_REST_URL"

# ── 6. Deploy genesis-ai ──────────────────────────────────────────────────────
echo "[ 6/8 ] Deploying genesis-ai..."
az containerapp create \
  -n "$APP_NAME" -g "$RESOURCE_GROUP" \
  --environment "$ENVIRONMENT" \
  --image "$ACR_SERVER/genesis-ai:latest" \
  --registry-server "$ACR_SERVER" \
  --registry-username "$ACR_USER" \
  --registry-password "$ACR_PASS" \
  --target-port 8080 \
  --ingress external \
  --transport http \
  --min-replicas 1 --max-replicas 3 \
  --cpu 1.0 --memory 2.0Gi \
  --env-vars \
    "ANTHROPIC_API_KEY=$ANTHROPIC_API_KEY" \
    "OPENAI_API_KEY=$OPENAI_API_KEY" \
    "GENESIS_API_KEY=$GENESIS_API_KEY" \
    "QDRANT_REST_URL=$QDRANT_REST_URL" \
    "QDRANT_API_KEY=$QDRANT_API_KEY_VALUE" \
    "QDRANT_COLLECTION=genesis-knowledge" \
  -o none

APP_FQDN=$(az containerapp show -n "$APP_NAME" -g "$RESOURCE_GROUP" \
  --query properties.configuration.ingress.fqdn -o tsv)

# ── 7. Save config ────────────────────────────────────────────────────────────
# ── 7. Storage account + knowledge container ──────────────────────────────────
echo "[ 7/8 ] Creating knowledge storage..."
az storage account create \
  --name "$STORAGE_ACCOUNT" \
  --resource-group "$RESOURCE_GROUP" \
  --location "$LOCATION" \
  --sku Standard_LRS \
  --kind StorageV2 \
  --output none

STORAGE_KEY=$(az storage account keys list \
  --account-name "$STORAGE_ACCOUNT" \
  --resource-group "$RESOURCE_GROUP" \
  --query "[0].value" -o tsv)

az storage container create \
  --name "$KNOWLEDGE_CONTAINER" \
  --account-name "$STORAGE_ACCOUNT" \
  --account-key "$STORAGE_KEY" \
  --output none

echo "   Storage: https://$STORAGE_ACCOUNT.blob.core.windows.net/$KNOWLEDGE_CONTAINER"

# ── 8. Save config ────────────────────────────────────────────────────────────
echo "[ 8/8 ] Saving deployment config..."
cat > deploy/.azure-config << EOF
RESOURCE_GROUP=$RESOURCE_GROUP
ACR_NAME=$ACR_NAME
ACR_SERVER=$ACR_SERVER
ENVIRONMENT=$ENVIRONMENT
QDRANT_APP=$QDRANT_APP
APP_NAME=$APP_NAME
QDRANT_REST_URL=$QDRANT_REST_URL
QDRANT_API_KEY=$QDRANT_API_KEY_VALUE
APP_URL=https://$APP_FQDN

STORAGE_ACCOUNT=$STORAGE_ACCOUNT
KNOWLEDGE_CONTAINER=$KNOWLEDGE_CONTAINER
STORAGE_KEY=$STORAGE_KEY
EOF

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                   Deployment complete!                       ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "  Genesis AI UI  →  https://$APP_FQDN"
echo "  Qdrant REST    →  $QDRANT_REST_URL"
echo ""
echo "Next steps:"
echo "  1. Upload knowledge:  bash deploy/upload-knowledge.sh"
echo "  2. Rebuild brain:     bash deploy/rebuild-brain.sh"
echo ""
