#!/usr/bin/env bash
# Genesis AI — redeploy after code changes (rebuild image + update Container App)
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

# Build image in ACR (cloud-side — no local Docker required)
echo "[ 1/2 ] Building image in ACR (this takes ~3 min)..."
TAG=$(date +%Y%m%d%H%M%S)
IMAGE="$ACR_SERVER/genesis-ai:$TAG"

az acr build -r "$ACR_NAME" -t "genesis-ai:$TAG" -t "genesis-ai:latest" . -o none

# Update Container App with unique tag to force image pull
echo "[ 2/2 ] Updating Container App..."
az containerapp update \
  -n "$APP_NAME" -g "$RESOURCE_GROUP" \
  --image "$IMAGE" \
  -o none

echo ""
echo "Done. Live at: $APP_URL"
echo ""
