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

# Build image locally and push to ACR
echo "[ 1/2 ] Building image locally and pushing to ACR..."
az acr login -n "$ACR_NAME" -o none
docker build -t "$ACR_SERVER/genesis-ai:latest" .
docker push "$ACR_SERVER/genesis-ai:latest"

# Update Container App with new image revision
echo "[ 2/2 ] Updating Container App..."
az containerapp update \
  -n "$APP_NAME" -g "$RESOURCE_GROUP" \
  --image "$ACR_SERVER/genesis-ai:latest" \
  -o none

echo ""
echo "Done. Live at: $APP_URL"
echo ""
