#!/usr/bin/env bash
# genesis-os — first deploy to Azure, fully isolated from genesis-ai (genesis-brands):
# its own Postgres server, its own Qdrant instance. Shares only the resource group and
# Container Apps environment (networking/billing boundary, not data).
# Run from repo root: bash deploy/deploy-genesis-os.sh
set -euo pipefail

RESOURCE_GROUP="genesis-ai-rg"
LOCATION="uksouth"
ENVIRONMENT="genesis-ai-env"
ACR_NAME="genesisai4195ff"
ACR_SERVER="$ACR_NAME.azurecr.io"

PG_SERVER="genesis-os-pg"
PG_ADMIN_USER="genesisosadmin"
PG_DB="genesis_os"
PG_PASSWORD="$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)"

QDRANT_APP="genesis-os-qdrant"
QDRANT_API_KEY_VALUE="$(openssl rand -hex 16)"

APP_NAME="genesis-os"
GENESIS_API_KEY_VALUE="$(openssl rand -hex 16)"
BASIC_AUTH_PASSWORD_VALUE="$(openssl rand -hex 12)"
CUSTOM_DOMAIN="platform.genesisbrands.ai"

if [ ! -f .env ]; then echo "ERROR: .env not found. Run from repo root."; exit 1; fi
set -o allexport; source .env; set +o allexport
for var in ANTHROPIC_API_KEY OPENAI_API_KEY; do
  [ -z "${!var:-}" ] && echo "ERROR: $var not set in .env" && exit 1
done

echo ""
echo "╔══════════════════════════════════════╗"
echo "║        genesis-os — Deploy           ║"
echo "╚══════════════════════════════════════╝"
echo ""
echo "Subscription : $(az account show --query name -o tsv)"
echo "Resource group: $RESOURCE_GROUP ($LOCATION, existing)"
echo ""

# ── 1. Standalone Postgres for genesis-os ─────────────────────────────────────
echo "[ 1/5 ] Creating standalone Postgres server: $PG_SERVER..."
az postgres flexible-server create \
  --name "$PG_SERVER" \
  --resource-group "$RESOURCE_GROUP" \
  --location "$LOCATION" \
  --admin-user "$PG_ADMIN_USER" \
  --admin-password "$PG_PASSWORD" \
  --sku-name Standard_B1ms \
  --tier Burstable \
  --version 16 \
  --storage-size 32 \
  --yes \
  -o none

az postgres flexible-server firewall-rule create \
  --resource-group "$RESOURCE_GROUP" \
  --name "$PG_SERVER" \
  --rule-name AllowAzureServices \
  --start-ip-address 0.0.0.0 \
  --end-ip-address 0.0.0.0 \
  -o none

az postgres flexible-server db create \
  --resource-group "$RESOURCE_GROUP" \
  --server-name "$PG_SERVER" \
  --database-name "$PG_DB" \
  -o none

SPRING_DATASOURCE_URL="jdbc:postgresql://$PG_SERVER.postgres.database.azure.com:5432/$PG_DB?sslmode=require"

# ── 2. Standalone Qdrant for genesis-os ────────────────────────────────────────
echo "[ 2/5 ] Deploying standalone Qdrant: $QDRANT_APP..."
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

# ── 3. Build genesis-os image in ACR (shared registry, separate repo) ─────────
echo "[ 3/5 ] Building genesis-os image in ACR (this takes a few min)..."
az acr build -r "$ACR_NAME" -f Dockerfile.genesis-os -t genesis-os:latest . -o none

ACR_USER=$(az acr credential show -n "$ACR_NAME" --query username -o tsv)
ACR_PASS=$(az acr credential show -n "$ACR_NAME" --query "passwords[0].value" -o tsv)

# ── 4. Deploy genesis-os Container App ─────────────────────────────────────────
echo "[ 4/5 ] Deploying genesis-os..."
az containerapp create \
  -n "$APP_NAME" -g "$RESOURCE_GROUP" \
  --environment "$ENVIRONMENT" \
  --image "$ACR_SERVER/genesis-os:latest" \
  --registry-server "$ACR_SERVER" \
  --registry-username "$ACR_USER" \
  --registry-password "$ACR_PASS" \
  --target-port 8080 \
  --ingress external \
  --transport http \
  --min-replicas 1 --max-replicas 2 \
  --cpu 0.5 --memory 1.0Gi \
  --env-vars \
    "ANTHROPIC_API_KEY=$ANTHROPIC_API_KEY" \
    "OPENAI_API_KEY=$OPENAI_API_KEY" \
    "GENESIS_API_KEY=$GENESIS_API_KEY_VALUE" \
    "BASIC_AUTH_USERNAME=genesis" \
    "BASIC_AUTH_PASSWORD=$BASIC_AUTH_PASSWORD_VALUE" \
    "APP_ENV=production" \
    "GENESIS_ALLOWED_HOSTS=$CUSTOM_DOMAIN" \
    "SPRING_DATASOURCE_URL=$SPRING_DATASOURCE_URL" \
    "SPRING_DATASOURCE_DRIVER=org.postgresql.Driver" \
    "SPRING_DATASOURCE_USERNAME=$PG_ADMIN_USER" \
    "SPRING_DATASOURCE_PASSWORD=$PG_PASSWORD" \
    "QDRANT_REST_URL=$QDRANT_REST_URL" \
    "QDRANT_API_KEY=$QDRANT_API_KEY_VALUE" \
    "QDRANT_COLLECTION=genesis-os-knowledge" \
  -o none

APP_FQDN=$(az containerapp show -n "$APP_NAME" -g "$RESOURCE_GROUP" \
  --query properties.configuration.ingress.fqdn -o tsv)

# ── 5. Save config ──────────────────────────────────────────────────────────────
echo "[ 5/5 ] Saving deployment config..."
cat > deploy/.azure-config-genesis-os << EOF
RESOURCE_GROUP=$RESOURCE_GROUP
ENVIRONMENT=$ENVIRONMENT
ACR_NAME=$ACR_NAME
ACR_SERVER=$ACR_SERVER
APP_NAME=$APP_NAME
APP_URL=https://$APP_FQDN
CUSTOM_DOMAIN=$CUSTOM_DOMAIN

PG_SERVER=$PG_SERVER
PG_ADMIN_USER=$PG_ADMIN_USER
PG_DB=$PG_DB
PG_PASSWORD=$PG_PASSWORD
SPRING_DATASOURCE_URL=$SPRING_DATASOURCE_URL

QDRANT_APP=$QDRANT_APP
QDRANT_REST_URL=$QDRANT_REST_URL
QDRANT_API_KEY=$QDRANT_API_KEY_VALUE

GENESIS_API_KEY=$GENESIS_API_KEY_VALUE
BASIC_AUTH_PASSWORD=$BASIC_AUTH_PASSWORD_VALUE
EOF

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                   genesis-os deployed!                       ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "  genesis-os (default hostname) → https://$APP_FQDN"
echo "  Qdrant REST                   → $QDRANT_REST_URL"
echo "  Postgres                      → $PG_SERVER.postgres.database.azure.com/$PG_DB"
echo ""
echo "Next: bind $CUSTOM_DOMAIN — add a CNAME record at your DNS provider pointing"
echo "  $CUSTOM_DOMAIN  ->  $APP_FQDN"
echo "then run: bash deploy/bind-genesis-os-domain.sh"
echo ""
