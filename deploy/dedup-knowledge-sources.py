#!/usr/bin/env python3
"""Find and remove duplicate knowledge sources, and report layer misassignments.

The corpus was ingested several times from different working copies, so most documents
exist two to four times over under different source paths (~/Downloads/Genesis Agent/...,
~/genesis-ai-knowledge/..., a scratchpad rebuild directory). Duplicates inflate retrieval
scores for whichever document happens to be duplicated most, which is a silent relevance bug.

This works entirely through the admin knowledge API, so it needs no Qdrant credentials:

    GET    /api/knowledge/sources          layer 1 sources
    GET    /api/knowledge/layer2/sources   layer 2 sources
    DELETE /api/knowledge/sources/{id}     remove one source and its chunks

DRY RUN BY DEFAULT. It prints the plan and changes nothing until you pass --apply.

    python3 deploy/dedup-knowledge-sources.py                      # plan, local
    python3 deploy/dedup-knowledge-sources.py --base https://www.genesisbrands.ai
    python3 deploy/dedup-knowledge-sources.py --base https://... --apply

Credentials are read from .env (ADMIN_USER / ADMIN_PASS for the dashboard's Basic auth,
GENESIS_API_KEY for the X-Api-Key header that /api/** requires) and are never printed.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import sys
import urllib.error
import urllib.request
from collections import defaultdict

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# A source path under the real knowledge repo is the canonical copy — prefer keeping it.
CANONICAL_PATH_HINT = "genesis-ai-knowledge"

# Documents that are the methodology template, not a client record. These belong in layer 1
# and are reported if found in layer 2.
TEMPLATE_TITLES = ["playbook v5"]


def load_credentials() -> dict[str, str]:
    """ADMIN_USER / ADMIN_PASS for the dashboard's Basic auth, GENESIS_API_KEY for /api/**."""
    env_path = os.path.join(REPO_ROOT, ".env")
    values: dict[str, str] = {}
    try:
        with open(env_path, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    key, value = line.split("=", 1)
                    values[key.strip()] = value.strip()
    except FileNotFoundError:
        sys.exit(f"No .env found at {env_path}")

    missing = [k for k in ("ADMIN_USER", "ADMIN_PASS", "GENESIS_API_KEY") if not values.get(k)]
    if missing:
        sys.exit("Missing from .env: " + ", ".join(missing))

    token = base64.b64encode(
        f"{values['ADMIN_USER']}:{values['ADMIN_PASS']}".encode()).decode()
    return {
        "Authorization": "Basic " + token,
        "X-Api-Key": values["GENESIS_API_KEY"],
        "User-Agent": "genesis-ops-dedup",
    }


def call(base: str, path: str, headers: dict[str, str], method: str = "GET"):
    request = urllib.request.Request(base.rstrip("/") + path, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            body = response.read()
            if response.status == 204 or not body:
                return None
            return json.loads(body)
    except urllib.error.HTTPError as err:
        sys.exit(f"{method} {path} -> {err.code} {err.reason}")
    except urllib.error.URLError as err:
        sys.exit(f"{method} {path} -> {err.reason}")


def document_key(source: dict, fuzzy: bool) -> str:
    """The identity of the underlying document, independent of where it was ingested from."""
    url = source.get("sourceUrl") or ""
    basename = url.replace("\\", "/").rstrip("/").split("/")[-1]
    name = basename or (source.get("title") or source.get("sourceId") or "")
    name = re.sub(r"\.(pdf|docx?|md|txt|html?)$", "", name, flags=re.IGNORECASE)
    name = re.sub(r"\s+", " ", name).strip().lower()
    if fuzzy:
        # "Safer AI" and "Safer AI-1" are the same document ingested twice. Opt-in, because
        # a trailing number is occasionally a real part of a document's name.
        name = re.sub(r"[-_ ]+\d+$", "", name).strip()
    return name


def rank(source: dict) -> tuple:
    """Higher sorts first: the copy we keep. Canonical repo path, then most chunks, then newest."""
    url = (source.get("sourceUrl") or "").lower()
    return (
        CANONICAL_PATH_HINT in url,
        source.get("chunkCount") or 0,
        source.get("ingestedAt") or "",
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8080", help="app base URL")
    parser.add_argument("--apply", action="store_true", help="actually delete the duplicates")
    parser.add_argument("--fuzzy", action="store_true",
                        help="also treat a trailing -1/-2 suffix as the same document")
    args = parser.parse_args()

    headers = load_credentials()

    layers = {
        "layer1": call(args.base, "/api/knowledge/sources", headers) or [],
        "layer2": call(args.base, "/api/knowledge/layer2/sources", headers) or [],
    }

    print(f"=== {args.base} ===")
    for layer, sources in layers.items():
        print(f"  {layer}: {len(sources)} source entries")
    print()

    total_to_delete: list[tuple[str, dict]] = []

    # Methodology documents sitting in layer 2. Every copy goes, not just the duplicates:
    # the file now lives in layer1/sources and gets re-ingested there, so leaving one
    # survivor behind in layer 2 would reintroduce exactly the problem being fixed.
    misassigned = [
        s for s in layers["layer2"]
        if any(t in ((s.get("sourceUrl") or "") + (s.get("title") or "")).lower()
               for t in TEMPLATE_TITLES)
    ]
    misassigned_ids = {s["sourceId"] for s in misassigned}

    for layer, sources in layers.items():
        groups: dict[str, list[dict]] = defaultdict(list)
        for source in sources:
            if source["sourceId"] in misassigned_ids:
                continue  # handled wholesale below, not as a duplicate group
            groups[document_key(source, args.fuzzy)].append(source)

        duplicated = {k: v for k, v in groups.items() if len(v) > 1}
        unique_documents = len(groups)

        print(f"--- {layer}: {unique_documents} unique documents, "
              f"{len(duplicated)} of them duplicated ---")

        for key, copies in sorted(duplicated.items()):
            copies.sort(key=rank, reverse=True)
            keep, remove = copies[0], copies[1:]
            print(f"\n  {key or '(untitled)'}  — {len(copies)} copies")
            print(f"    KEEP   {keep['sourceId']}  {keep.get('chunkCount', 0):>3} chunks  "
                  f"{keep.get('sourceUrl', '')}")
            for source in remove:
                print(f"    DELETE {source['sourceId']}  {source.get('chunkCount', 0):>3} chunks  "
                      f"{source.get('sourceUrl', '')}")
                total_to_delete.append((layer, source))
        print()

    # ── Layer misassignment ──────────────────────────────────────────────────
    if misassigned:
        print("--- Layer misassignment ---")
        print("  These are in layer 2 but are methodology, not a client record. Layer 2 is")
        print("  cross-engagement precedent; a blank template retrieved as precedent teaches")
        print("  the agents nothing and crowds out real engagements.")
        for source in misassigned:
            print(f"    DELETE {source['sourceId']}  {source.get('chunkCount', 0):>3} chunks  "
                  f"{source.get('sourceUrl', '')}")
            total_to_delete.append(("layer2", source))
        print()
        print("  All copies go, not just the duplicates. The file has already been moved in")
        print("  the knowledge repo:")
        print("    layer2/client-playbooks/Playbook v5.pdf -> layer1/sources/Playbook v5.pdf")
        print("  so ingest-all.sh ingests it as layer1 with no script change. After --apply,")
        print("  re-ingest that one file so the methodology is in layer 1:")
        print("    cd genesis-ai/ingestion && source .venv/bin/activate")
        print("    python ingest.py --file ~/genesis-ai-knowledge/'layer1/sources/Playbook v5.pdf' \\")
        print("        --layer layer1")
        print()

    if not total_to_delete:
        print("Nothing to delete.")
        return

    chunks = sum(s.get("chunkCount", 0) for _, s in total_to_delete)
    print(f"=== {len(total_to_delete)} duplicate source entries "
          f"({chunks} chunks) would be removed ===")

    if not args.apply:
        print("\nDry run. Re-run with --apply to delete them.")
        if not args.fuzzy:
            print("Add --fuzzy to also collapse '<name>-1' variants of the same document.")
        return

    print()
    for layer, source in total_to_delete:
        source_id = source["sourceId"]
        call(args.base, f"/api/knowledge/sources/{source_id}", headers, method="DELETE")
        print(f"  deleted {layer} {source_id}")
    print(f"\nDone. Removed {len(total_to_delete)} duplicate source entries.")


if __name__ == "__main__":
    main()
