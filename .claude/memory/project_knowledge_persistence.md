---
name: project-knowledge-persistence
description: "genesis-ai-knowledge repo created at ~/genesis-ai-knowledge and pushed to github.com/sauloos/genesis-ai-knowledge"
metadata: 
  node_type: memory
  type: project
  originSessionId: ee8e2bce-3dfc-495d-bca7-7e7c56639ed3
---

**DONE** — `genesis-ai-knowledge` repo created at `~/genesis-ai-knowledge` and pushed to https://github.com/sauloos/genesis-ai-knowledge (private).

**Structure:**
```
genesis-ai-knowledge/
  layer1/
    books/the-crisis-catalyst/     PDF + PPTX
    courses/heartset/              PDF + 6 .url + 6 .txt transcripts (normalised)
    youtube/purposepeople/         channel.url + 183 transcripts
    youtube/warehouse-church/      URL files + 5 Darrell transcripts
    blogs/cre8ion/                 index.url (re-crawled at ingest time)
    sources/                       engagement-model.md
  layer2/
    client-playbooks/              24 client PDFs
  ingest-all.sh                    rebuilds Qdrant from scratch
```

**ingest.py additions:** `--txt FILE` and `--no-normalise` flags added so ingest-all.sh can ingest saved transcripts without re-downloading or re-normalising.

**Note on transcripts:** Currently stored as normalised text (from chunk files). Raw transcripts not available — future new content should save raw text at extraction time.

**Why:** Separates knowledge corpus from code. Anyone can rebuild Qdrant from scratch with ingest-all.sh.
