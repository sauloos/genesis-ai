#!/usr/bin/env bash
# Download knowledge from Azure Blob and reingest everything into Qdrant.
# Run from repo root: bash deploy/rebuild-brain.sh
# Use QDRANT_URL / QDRANT_API_KEY env vars to target a different Qdrant instance.
set -euo pipefail

CONFIG="deploy/.azure-config"
if [ ! -f "$CONFIG" ]; then echo "ERROR: $CONFIG not found. Run deploy.sh first."; exit 1; fi
source "$CONFIG"

QDRANT_URL="${QDRANT_URL:-$QDRANT_REST_URL}"
QDRANT_KEY="${QDRANT_API_KEY:-}"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

echo ""
echo "╔══════════════════════════════════════════════════════╗"
echo "║         Genesis AI — Rebuild Brain from Blob         ║"
echo "╚══════════════════════════════════════════════════════╝"
echo ""
echo "  Blob    : https://$STORAGE_ACCOUNT.blob.core.windows.net/$KNOWLEDGE_CONTAINER"
echo "  Qdrant  : $QDRANT_URL"
echo "  Staging : $WORK_DIR"
echo ""

# ── 1. Download from Blob ─────────────────────────────────────────────────────
echo "[ 1/2 ] Downloading knowledge from Blob..."
az storage blob download-batch \
  --source "$KNOWLEDGE_CONTAINER" \
  --destination "$WORK_DIR" \
  --account-name "$STORAGE_ACCOUNT" \
  --account-key "$STORAGE_KEY" \
  --output none

echo "        Downloaded $(find "$WORK_DIR" -type f | wc -l | tr -d ' ') files."
echo ""

# ── 2. Ingest into Qdrant ─────────────────────────────────────────────────────
echo "[ 2/2 ] Ingesting into Qdrant..."

cd ingestion
source .venv/bin/activate 2>/dev/null || {
  echo "ERROR: venv not found. Run:"
  echo "  cd ingestion && python3 -m venv .venv && pip install -r requirements.txt"
  exit 1
}

if [ -f "$WORK_DIR/ingest-all.sh" ]; then
  # Run the knowledge repo's own ingest script if present, pointed at the download
  QDRANT_URL="$QDRANT_URL" QDRANT_API_KEY="$QDRANT_KEY" \
    bash "$WORK_DIR/ingest-all.sh" "$WORK_DIR"
else
  # Fallback: ingest layer1 and layer2 directories directly
  for dir in layer1 layer2; do
    if [ -d "$WORK_DIR/$dir" ]; then
      echo "  Ingesting $dir..."
      find "$WORK_DIR/$dir" -type f \( -name "*.txt" -o -name "*.md" -o -name "*.pdf" \) | while read -r file; do
        QDRANT_URL="$QDRANT_URL" QDRANT_API_KEY="$QDRANT_KEY" \
          python ingest.py --txt "$file" --no-normalise 2>/dev/null || true
      done
    fi
  done
fi

echo ""
echo "Brain rebuild complete."
echo "  Qdrant: $QDRANT_URL"
echo ""
