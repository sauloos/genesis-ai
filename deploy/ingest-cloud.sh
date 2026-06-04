#!/usr/bin/env bash
# Re-ingest all knowledge sources into the cloud Qdrant instance.
# Run from repo root: bash deploy/ingest-cloud.sh
set -euo pipefail

CONFIG="deploy/.azure-config"
if [ ! -f "$CONFIG" ]; then echo "ERROR: $CONFIG not found. Run deploy.sh first."; exit 1; fi
source "$CONFIG"

cd ingestion
source .venv/bin/activate 2>/dev/null || { echo "ERROR: venv not found. Run: cd ingestion && python3 -m venv .venv && pip install -r requirements.txt"; exit 1; }

echo ""
echo "Ingesting into: $QDRANT_REST_URL"
echo ""

QDRANT_URL="$QDRANT_REST_URL" QDRANT_API_KEY="$QDRANT_API_KEY" \
  python ingest.py --crawl https://cre8ion.co.uk/blog/ --force

QDRANT_URL="$QDRANT_REST_URL" QDRANT_API_KEY="$QDRANT_API_KEY" \
  python ingest.py --channel https://www.youtube.com/@purposepeople

echo ""
echo "Ingestion complete."
