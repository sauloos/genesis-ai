#!/usr/bin/env bash
# Upload local genesis-ai-knowledge repo to Azure Blob Storage.
# Run from repo root: bash deploy/upload-knowledge.sh [/path/to/genesis-ai-knowledge]
# Defaults to ~/genesis-ai-knowledge if no path given.
set -euo pipefail

CONFIG="deploy/.azure-config"
if [ ! -f "$CONFIG" ]; then echo "ERROR: $CONFIG not found. Run deploy.sh first."; exit 1; fi
source "$CONFIG"

KNOWLEDGE_DIR="${1:-$HOME/genesis-ai-knowledge}"
if [ ! -d "$KNOWLEDGE_DIR" ]; then
  echo "ERROR: Knowledge directory not found: $KNOWLEDGE_DIR"
  echo "Usage: bash deploy/upload-knowledge.sh [/path/to/genesis-ai-knowledge]"
  exit 1
fi

echo ""
echo "╔══════════════════════════════════════════════════════╗"
echo "║         Genesis AI — Upload Knowledge to Blob        ║"
echo "╚══════════════════════════════════════════════════════╝"
echo ""
echo "  Source : $KNOWLEDGE_DIR"
echo "  Target : https://$STORAGE_ACCOUNT.blob.core.windows.net/$KNOWLEDGE_CONTAINER"
echo ""

az storage blob upload-batch \
  --source "$KNOWLEDGE_DIR" \
  --destination "$KNOWLEDGE_CONTAINER" \
  --account-name "$STORAGE_ACCOUNT" \
  --account-key "$STORAGE_KEY" \
  --overwrite true \
  --output none

COUNT=$(az storage blob list \
  --container-name "$KNOWLEDGE_CONTAINER" \
  --account-name "$STORAGE_ACCOUNT" \
  --account-key "$STORAGE_KEY" \
  --query "length(@)" -o tsv)

echo "Done — $COUNT files in blob."
echo ""
