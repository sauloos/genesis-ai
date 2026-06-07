---
name: project_build_progress
description: "Current state of Genesis AI — Brain Engine live, consultant mode deployed, roadmap to Beta EOY 2026"
metadata: 
  node_type: memory
  type: project
  originSessionId: a0a36664-fd48-4030-ad99-fb64466d0c0b
---

## Status as of 2026-06-07

### What's live today

**Brain Engine — LIVE**
Consultant mode is fully deployed and operational at:
https://genesis-ai.bravegrass-671fd7f3.uksouth.azurecontainerapps.io

The Brain Engine (Claude Opus 4 + extended thinking + two-layer knowledge base) is live, providing brand insights, answering strategic questions, and proving its intelligence. A formal demo is scheduled within the next ~2 weeks.

**Knowledge base (fully ingested):**
- Layer 1: engagement-model, Crisis Catalyst, HeartSet course, 183 Purpose People YouTube episodes, 5 Warehouse Church Darrell videos, 427 cre8ion blog posts
- Layer 2: 24 client playbooks
- Ingested with GPT-4o-mini normalisation; vector store: Qdrant

**Chat features live:**
- File attachments (PDF, TXT, MD) — ephemeral context injection
- Auto URL fetching — paste any URL, fetched and injected as context
- Client confidentiality rule in system prompt

### Roadmap

| Phase | Status | What |
|---|---|---|
| Alpha (~5 years ago) | ✓ Complete | Decision-tree pipeline, PDF brand books, real clients, testimonials on record |
| Brain Engine / Consultant mode | ✓ LIVE | Deployed, being tested, demo scheduled |
| Agent pipeline integration | Next | Connect Copy, Visual, Logo, Playbook, Brand Book agents; refine Brain Engine's prompting & inspection loop |
| User UI & questionnaire | Then | Client-facing discovery experience |
| Full integration & QA | Then | Connect all dots |
| **Beta launch** | **EOY 2026** | Full pipeline open to early subscribers |

### Azure resources
- Resource group: `genesis-ai-rg` (uksouth)
- ACR: `genesisaiacrqz0nwg.azurecr.io`
- Container Apps environment: `genesis-ai-env`
- Qdrant: `genesis-qdrant` — healthy
- Qdrant URL: see `deploy/.azure-config` (gitignored)
- Qdrant API key: see `deploy/.azure-config` (gitignored)
- X-API-Key: see `deploy/.azure-config` (gitignored)
- Deploy config: `deploy/.azure-config` (gitignored)

### Key lesson: Docker platform on Apple Silicon
Always build with `--platform linux/amd64` for Azure Container Apps.
`deploy/redeploy.sh` has this. **Never remove it.**

### To redeploy
```bash
bash deploy/redeploy.sh
```

**Why:** [[project_demo_scope]], [[project_genesis_ai_two_modes]]
