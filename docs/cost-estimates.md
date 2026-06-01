# Genesis Brands — Infrastructure Cost Estimates

> Figures are rough monthly averages based on Azure West Europe pricing (April 2026).
> All costs in USD. Exchange rate fluctuations and Azure price changes will apply.
> AI/OpenAI costs are usage-driven and shown separately — they scale directly with brand generation volume.

---

## Dev Environment

Sized for a single shared cloud dev/integration environment.
Local development runs on Docker Compose; this cluster is for integration testing and shared QA.

| Service | Configuration | Est. Cost/mo |
|---|---|---|
| AKS | 2× Standard_D4s_v3 (4 vCPU / 16 GB each) | $280 |
| PostgreSQL Flexible Server | Burstable B2s, no HA, 32 GB storage | $30 |
| Cosmos DB | Serverless mode (pay per request) | $10 |
| Redis Cache | Basic C0 (250 MB) | $16 |
| Azure Front Door | Standard tier | $35 |
| Service Bus | Basic tier | $2 |
| Blob Storage | Hot tier, ~20 GB | $5 |
| Container Registry | Basic tier | $5 |
| Azure Monitor | Log Analytics, ~5 GB/mo | $10 |
| Key Vault + App Configuration | Standard | $4 |
| Azure OpenAI | Light testing (~50 generations) | $20 |
| Image Generation API (Stability AI) | Light testing | $10 |
| **Total** | | **~$430/mo** |

### Dev cost reduction options

- Run all services locally via Docker Compose and only use the cloud cluster for CI/integration runs → reduces to **~$180–220/mo** (just databases, storage, and a single small AKS node).
- Use Azure Dev/Test pricing for VMs (up to 55% discount if team has Visual Studio subscriptions).
- Shut down the AKS cluster outside business hours with an automation runbook → saves ~$130/mo.

---

## Production — Early Stage / MVP

Sized for launch through to ~500 brand generations/month, modest concurrent users.

| Service | Configuration | Est. Cost/mo |
|---|---|---|
| AKS | 3 system nodes (D2s_v3) + 4 user nodes (D4s_v3), HPA enabled | $840 |
| PostgreSQL Flexible Server | General Purpose D2ds_v4, zone-redundant HA, 128 GB | $280 |
| Cosmos DB | 2,000 RU/s autoscale (scales down to 200 RU/s at idle) | $120 |
| Redis Cache | Standard C1 (1 GB, zone-redundant) | $110 |
| Azure Front Door | Standard tier + WAF policy | $80 |
| Service Bus | Standard tier | $15 |
| Blob Storage | Hot tier, ~100 GB + CDN egress | $30 |
| Container Registry | Standard tier | $20 |
| Azure Monitor | Log Analytics + alerts, ~20 GB/mo | $60 |
| Key Vault + App Configuration | Standard | $6 |
| Azure OpenAI | ~500 brand generations/mo (see AI cost table below) | $200 |
| Image Generation API | ~500 brands × 5 images @ $0.04/image | $100 |
| SendGrid | Essentials 50K emails/mo | $15 |
| **Total** | | **~$1,880/mo** |

---

## Production — Scaling

Sized for 2,000–5,000 brand generations/month, growing subscriber base.

| Service | Configuration | Est. Cost/mo |
|---|---|---|
| AKS | 3 system + 6–8 user nodes (D4s_v3), spot instances for generation pods | $1,100–1,400 |
| PostgreSQL Flexible Server | D4ds_v4, zone-redundant HA, 256 GB, read replica | $600 |
| Cosmos DB | 5,000–10,000 RU/s autoscale | $290–580 |
| Redis Cache | Standard C2 (6 GB) | $220 |
| Azure Front Door | Standard + WAF | $120 |
| Service Bus | Standard (multiple topics/subscriptions) | $30 |
| Blob Storage | ~500 GB hot + archive tier for old assets | $80 |
| Container Registry | Standard | $20 |
| Azure Monitor | Log Analytics + Application Insights | $120 |
| Key Vault + App Configuration | Standard | $8 |
| Azure OpenAI | ~3,000 generations/mo | $900–1,500 |
| Image Generation API | ~3,000 brands × 5 images | $600 |
| SendGrid | Pro 100K emails/mo | $90 |
| **Total** | | **~$4,200–5,800/mo** |

---

## AI Cost Breakdown — The Key Variable

Azure OpenAI (GPT-4o) costs scale directly with brand generation volume.
Each brand generation consumes tokens across the questionnaire analysis, copy generation, and visual prompting phases.

**Estimated token cost per brand generation:**

| Phase | Approx. tokens | Cost (GPT-4o) |
|---|---|---|
| Conversation / signal extraction | 8K input + 2K output | ~$0.024 |
| Copy generation (taglines, bio, tone) | 20K input + 8K output | ~$0.130 |
| Visual prompt generation | 5K input + 2K output | ~$0.033 |
| **Total per brand** | ~45K tokens | **~$0.19–0.35** |

> Input is cheaper with prompt caching (repeated system prompts cached at $0.625/1M vs $2.50/1M).
> Assume ~$0.25–0.35 per generation as a working figure.

**Monthly AI cost by volume:**

| Generations/month | OpenAI cost | Image Gen cost | Total AI cost |
|---|---|---|---|
| 100 | $30 | $20 | $50 |
| 500 | $150 | $100 | $250 |
| 2,000 | $600 | $400 | $1,000 |
| 5,000 | $1,500 | $1,000 | $2,500 |
| 20,000 | $6,000 | $4,000 | $10,000 |

At 20K generations/month, AI becomes the dominant cost line. This is also where unit economics must be healthy — the brand subscription or per-generation fee needs to cover $0.50–0.70 in raw AI cost with enough margin.

---

## Cost Reduction Levers

| Lever | Saving | Notes |
|---|---|---|
| AKS Reserved Instances (1-year) | ~35% off compute | Locks you into the VM size for 12 months |
| Spot instances for generation pods | ~60–70% off those nodes | Acceptable: generation is async and retriable |
| Cosmos DB autoscale | Scales down at idle | Already included in estimates above |
| PostgreSQL reserved capacity (1-year) | ~33% off | Good to apply once DB size is stable |
| Azure for Startups programme | $25K–$150K credits | Apply early — covers 6–18 months of prod |
| Prompt caching (Azure OpenAI) | ~25–40% off input tokens | Already baked into estimates above |
| Tiered Blob Storage | Archive old brand assets | Move assets >180 days old to Cool tier |

---

## Summary

| Environment | Monthly Cost |
|---|---|
| Local dev (Docker Compose only) | ~$0 |
| Dev/integration cloud environment | ~$400–500 |
| Production — MVP (launch) | ~$1,500–2,000 |
| Production — scaling (2–5K gen/mo) | ~$4,000–6,000 |

The path from MVP to scaling is not a cliff — costs grow gradually as AKS nodes scale and Cosmos/PostgreSQL tiers increase. The biggest non-linear jump happens in AI costs once generation volume takes off, which should coincide with revenue that comfortably covers it.
