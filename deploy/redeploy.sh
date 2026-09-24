#!/usr/bin/env bash
# Genesis AI — redeploy after code changes (rebuild image in ACR + update Container App)
# Run from repo root:
#   bash deploy/redeploy.sh                                              # genesis-ai (default)
#   bash deploy/redeploy.sh deploy/.azure-config-genesis-os Dockerfile.genesis-os   # genesis-os
set -euo pipefail

CONFIG="${1:-deploy/.azure-config}"
DOCKERFILE="${2:-Dockerfile}"
if [ ! -f "$CONFIG" ]; then echo "ERROR: $CONFIG not found. Run deploy.sh first."; exit 1; fi
source "$CONFIG"
source .env 2>/dev/null || true

echo ""
echo "╔══════════════════════════════════════╗"
echo "║   $APP_NAME — Redeploy"
echo "╚══════════════════════════════════════╝"
echo ""

TAG=$(date +%Y%m%d%H%M%S)

# Build image in ACR (cloud build — no local Docker required)
echo "[ 1/2 ] Building image in ACR from $DOCKERFILE (this takes ~3 min)..."
az acr build \
  -r "$ACR_NAME" \
  -f "$DOCKERFILE" \
  -t "$APP_NAME:$TAG" \
  -t "$APP_NAME:latest" \
  . -o none

# Update Container App with the new tagged image to force a fresh pull.
# Also pass through any API keys from .env that have been set/changed.
echo "[ 2/2 ] Updating Container App..."
ENV_OVERRIDES=""
[ -n "${UNSPLASH_ACCESS_KEY:-}" ]  && ENV_OVERRIDES="$ENV_OVERRIDES UNSPLASH_ACCESS_KEY=$UNSPLASH_ACCESS_KEY"
[ -n "${IDEOGRAM_API_KEY:-}" ]     && ENV_OVERRIDES="$ENV_OVERRIDES IDEOGRAM_API_KEY=$IDEOGRAM_API_KEY"
ENV_OVERRIDES="${ENV_OVERRIDES# }"   # trim leading space

if [ -n "$ENV_OVERRIDES" ]; then
  az containerapp update \
    -n "$APP_NAME" -g "$RESOURCE_GROUP" \
    --image "$ACR_SERVER/$APP_NAME:$TAG" \
    --set-env-vars $ENV_OVERRIDES \
    -o none
else
  az containerapp update \
    -n "$APP_NAME" -g "$RESOURCE_GROUP" \
    --image "$ACR_SERVER/$APP_NAME:$TAG" \
    -o none
fi

echo ""
echo "Done. Live at: $APP_URL"
echo ""
