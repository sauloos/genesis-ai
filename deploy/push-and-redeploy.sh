#!/usr/bin/env bash
# Genesis AI — push + redeploy in one shot, meant to be run directly by the user
# (e.g. via Claude Code's "!" bash-passthrough), not by the agent.
#
# Pushes main, then rebuilds/redeploys via deploy/redeploy.sh. If the local-upload
# ACR build hits the SAS-token auth error that's been intermittent, falls back to
# building straight from the pushed GitHub branch instead of a local tar upload.
#
# Run from repo root:
#   bash deploy/push-and-redeploy.sh
set -euo pipefail

cd "$(dirname "$0")/.."

echo "[ 1/3 ] Pushing to origin/main..."
git push

echo "[ 2/3 ] Building + redeploying (local-upload path)..."
if bash deploy/redeploy.sh; then
  exit 0
fi

echo "Local-upload build failed — falling back to GitHub-context build..."
source deploy/.azure-config
TAG=$(date +%Y%m%d%H%M%S)

echo "[ 3/3 ] Building image in ACR from GitHub main branch..."
az acr build \
  -r "$ACR_NAME" \
  -f Dockerfile \
  -t "$APP_NAME:$TAG" \
  -t "$APP_NAME:latest" \
  "https://github.com/sauloos/genesis-ai.git#main" \
  -o none

az containerapp update \
  -n "$APP_NAME" -g "$RESOURCE_GROUP" \
  --image "$ACR_SERVER/$APP_NAME:$TAG" \
  -o none

echo ""
echo "Done. Live at: $APP_URL"
echo ""
