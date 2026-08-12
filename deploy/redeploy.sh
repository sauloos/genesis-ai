#!/usr/bin/env bash
# Genesis AI — redeploy after code changes (rebuild image in ACR + update Container App)
# Run from repo root: bash deploy/redeploy.sh
set -euo pipefail

CONFIG="deploy/.azure-config"
if [ ! -f "$CONFIG" ]; then echo "ERROR: $CONFIG not found. Run deploy.sh first."; exit 1; fi
source "$CONFIG"
source .env 2>/dev/null || true

echo ""
echo "╔══════════════════════════════════════╗"
echo "║        Genesis AI — Redeploy         ║"
echo "╚══════════════════════════════════════╝"
echo ""

TAG=$(date +%Y%m%d%H%M%S)

# Build image in ACR (cloud build — no local Docker required)
echo "[ 1/2 ] Building image in ACR (this takes ~3 min)..."
az acr build \
  -r "$ACR_NAME" \
  -t "genesis-ai:$TAG" \
  -t "genesis-ai:latest" \
  . -o none

# Update Container App with the new tagged image to force a fresh pull.
# Also pass through any API keys from .env that have been set/changed.
echo "[ 2/2 ] Updating Container App..."
ENV_OVERRIDES=""
[ -n "${UNSPLASH_ACCESS_KEY:-}" ] && ENV_OVERRIDES="UNSPLASH_ACCESS_KEY=$UNSPLASH_ACCESS_KEY"

if [ -n "$ENV_OVERRIDES" ]; then
  az containerapp update \
    -n "$APP_NAME" -g "$RESOURCE_GROUP" \
    --image "$ACR_SERVER/genesis-ai:$TAG" \
    --set-env-vars $ENV_OVERRIDES \
    -o none
else
  az containerapp update \
    -n "$APP_NAME" -g "$RESOURCE_GROUP" \
    --image "$ACR_SERVER/genesis-ai:$TAG" \
    -o none
fi

echo ""
echo "Done. Live at: $APP_URL"
echo ""
