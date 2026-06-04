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
TAG=$(date +%Y%m%d%H%M%S)
IMAGE="$ACR_SERVER/genesis-ai:$TAG"

az acr login -n "$ACR_NAME" -o none
docker build --no-cache -t "$IMAGE" .
docker push "$IMAGE"

# Also tag as latest for reference
docker tag "$IMAGE" "$ACR_SERVER/genesis-ai:latest"
docker push "$ACR_SERVER/genesis-ai:latest"

# Update Container App with unique tag to force image pull
echo "[ 2/2 ] Updating Container App..."
az containerapp update \
  -n "$APP_NAME" -g "$RESOURCE_GROUP" \
  --image "$IMAGE" \
  -o none

echo ""
echo "Done. Live at: $APP_URL"
echo ""
