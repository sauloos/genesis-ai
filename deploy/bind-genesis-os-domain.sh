#!/usr/bin/env bash
# genesis-os — bind platform.genesisbrands.ai + managed cert, once its DNS records
# (see the printout from deploy-genesis-os.sh) have been added at your DNS provider
# and have propagated. Run from repo root: bash deploy/bind-genesis-os-domain.sh
set -euo pipefail

CONFIG="deploy/.azure-config-genesis-os"
if [ ! -f "$CONFIG" ]; then echo "ERROR: $CONFIG not found. Run deploy-genesis-os.sh first."; exit 1; fi
source "$CONFIG"

echo "Binding $CUSTOM_DOMAIN to $APP_NAME..."
az containerapp hostname bind \
  --hostname "$CUSTOM_DOMAIN" \
  -g "$RESOURCE_GROUP" \
  -n "$APP_NAME" \
  --environment "$ENVIRONMENT" \
  --validation-method CNAME

echo ""
echo "Done. Live at: https://$CUSTOM_DOMAIN"
echo ""
