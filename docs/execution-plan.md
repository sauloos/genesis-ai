# Genesis Brands — Execution Plan

## Phase 0 — Demo: Genesis AI Consultant
> Goal: demonstrate the intelligence behind the platform to potential customers, without
> committing to asset generation. Show the brain — the methodology, the reasoning, the
> creative director voice — before the full pipeline is built.

**Scope:** Consultant mode only. Internal-facing. No brand asset generation.

### What we're building

- [ ] Layer 1 knowledge base: seed `knowledge/layer1/sources/` with initial knowledge
      sources (blog posts, frameworks, methodology docs) and author the first Layer 1b
      reasoning modules from them
- [ ] Genesis AI consultant agent: Claude Opus 4, extended thinking, loaded with Layer 1b
      modules and brand context, conversational creative director voice
- [ ] Minimal BrandDNA input: a lightweight intake form (or even a free-text brief) to
      give Genesis AI brand context before the conversation starts — no full questionnaire yet
- [ ] Chat interface: streaming conversation UI, per-brand conversation history
- [ ] Demo environment: simple, self-contained — no microservices, no auth complexity

### What this demo proves to potential customers

- Genesis AI knows how to think about brands (Layer 1 methodology in action)
- It gives opinionated, expert advice — not generic AI output
- It reasons from past precedent (even if Layer 2 is thin at demo stage)
- It can conduct a strategic brand conversation at the level of a senior creative director
- The full pipeline (copy, visual, assets) is the natural next step from this intelligence

### What this demo deliberately excludes

- Asset generation (copy, logo, visual identity, brand book)
- The full client questionnaire flow
- Specialist agents
- The async pipeline / Service Bus / microservices stack
- Payments, delivery, admin

> **Why this order:** Asset generation quality depends on Layer 1 being deep and Layer 2
> being populated with precedent. Building the full pipeline before the knowledge base is
> ready produces generic output that undersells what Genesis AI will become. The consultant
> demo proves the intelligence first.

---

## Phase 1 — Core Platform (MVP)
> Goal: end-to-end AI brand generation working for a paying customer

- [ ] Infrastructure setup: AKS cluster, Cosmos DB, PostgreSQL, Blob Storage, Service Bus, Redis (Azure)
- [ ] Identity Service: registration, login, JWT, subscription plans
- [ ] Conversation Service: LLM questionnaire flow, signal extraction → BrandDNA draft
- [ ] Brand Engine: orchestrator, BrandDNA service, state machine (AI path only)
- [ ] Copy Generator: mission, values, taglines, tone of voice — prompt-engineering baseline
- [ ] Visual Generator: logo, colour palette, typography (Azure OpenAI + Stability/Flux) — prompt-engineering baseline
- [ ] Asset Composer: assemble assets from BrandDNA
- [ ] Delivery Service: PDF brand book generation, ZIP download
- [ ] React SPA: onboarding wizard, dashboard, asset download
- [ ] Stripe integration: payment on checkout (Essential £179, Premium £279)
- [ ] Notification Service: email (SendGrid) for generation complete, delivery
- [ ] Admin Service: questionnaire config, prompt templates, tier rules
- [ ] Strapi CMS: marketing copy, email templates, blog

> **Generation quality note — Phase 1:** Copy and Visual generators run on detailed prompt engineering
> with explicit style rules and design principles. Output quality is good but generic — the house
> style library that makes output distinctly Genesis Brands is built in Phase 2.

## Phase 2 — Print, Merch, Growth & Generation Intelligence
> Goal: expand revenue streams, stickiness, and make generation output distinctly Genesis Brands

- [ ] Order Service: print & merch routing
- [ ] Print Provider adapters: Printful, MOO, Gelato
- [ ] Merch Provider adapters: Printify
- [ ] Ultimate tier: £679, printed items fulfilment
- [ ] Social media management: AI-generated content, recurring
- [ ] Website tier: hosted brand microsite (recurring)
- [ ] Brand evolution: EVOLVING state, consistency monitoring
- [ ] White label / B2B2C: agency and accelerator portals
- [ ] A/B config, feature flags (Azure App Config)
- [ ] Analytics & observability: dashboards, alerting

### Generation Intelligence — House Style Library

> Goal: move from generic AI output to Genesis Brands house style. Both text copy and visual
> generation evolve through the same three-stage approach below.

**Stage 1 — Content Library foundation (Phase 2 start)**
- [ ] Enable pgvector extension on PostgreSQL
- [ ] Design and implement Content Library schema (see data model below)
- [ ] Build embedding pipeline: on content approval, generate embedding via Azure OpenAI
      and store in PostgreSQL with brand signals as metadata
- [ ] Seed copy library: manually curate 50–100 approved examples per content type
      (taglines, brand stories, tone-of-voice guides, mission statements)
- [ ] Seed visual library: manually curate 50–100 approved visual direction descriptions
      and image prompt patterns per brand personality archetype
- [ ] Admin panel: "Approve for library" action on any generated brand output
      (copy block, visual direction, logo concept) — human curation gate, no auto-ingestion

**Stage 2 — RAG retrieval layer (Phase 2 mid)**
- [ ] Build Content Library Service (module within Brand Engine)
      — query by BrandDNA signals, content type, quality tier
      — returns top N most similar approved examples via vector similarity search
- [ ] Update Copy Generator: retrieve 3–5 similar approved copy examples before generation;
      inject as style anchors in system prompt
- [ ] Update Visual Generator: retrieve similar approved visual direction examples;
      inject as style reference in image prompts
- [ ] Update Asset Composer: retrieve approved layout patterns for similar brand personalities
- [ ] Measure output quality before/after — track human approval rate per generation

**Stage 3 — Fine-tuned image model (Phase 2 end / Phase 3 gate)**
- [ ] Evaluate when library reaches 200+ approved visual examples
- [ ] Fine-tune Stability AI / Flux model on curated Genesis Brands visual library
      (DreamBooth or LoRA training on approved logo concepts and brand imagery)
- [ ] Run fine-tuned model behind Provider Abstraction Layer — A/B test against base model
- [ ] Promote fine-tuned model when quality metrics exceed base model consistently
- [ ] Re-train on an ongoing schedule as library grows (quarterly or milestone-triggered)

### Content Library — Data Model (PostgreSQL + pgvector)

```sql
content_library (
  id              uuid primary key,
  type            varchar   -- 'tagline' | 'brand_story' | 'tone_guide' | 'mission'
                            -- | 'visual_direction' | 'image_prompt' | 'layout_pattern'
  content         text,     -- the actual copy or prompt text
  brand_signals   jsonb,    -- industry, audience, personality[] — for retrieval filtering
  quality_tier    varchar,  -- 'approved' | 'featured' | 'deprecated'
  source_brand_id uuid,     -- which brand it was generated for (nullable for manual seeds)
  approved_by     uuid,     -- admin user who approved it
  approved_at     timestamptz,
  embedding       vector(1536),  -- Azure OpenAI text-embedding-3-small
  created_at      timestamptz
)
```

### Architectural notes
- Content Library is a module inside Brand Engine, not a standalone service at this scale
- Retrieval query: filter by `type` + `quality_tier = 'approved'` + signal overlap,
  order by cosine similarity on embedding, limit 5
- pgvector handles this well up to ~100K entries; migrate to Azure AI Search if library
  exceeds that or if hybrid keyword+vector search is needed
- The Provider Abstraction Layer already isolates the image generation model —
  fine-tuned models plug in as a new adapter without touching Visual Generator logic
- Human curation is non-negotiable: auto-ingesting AI outputs causes quality drift

## Phase 3 — Designer Marketplace ⚠️ Low Priority
> Goal: human-design premium tier alongside AI generation; new revenue stream for designers

**When to build:** after Phase 1 is live and generating revenue. Build only if there is validated demand for non-AI brand work.

### What it is
Customers choosing "Human Fulfilment" get their BrandDNA brief published to a pool of vetted freelance designers. A designer claims the brief, uploads the finished assets, the customer approves (or requests revisions), and the designer gets paid via Stripe Connect.

### Work breakdown
- [ ] Designer Marketplace Service (see `docs/diagrams/05-designer-marketplace.mmd`)
  - Brief Service: publish BrandDNA as structured brief, expiry, exclusivity window
  - Assignment Service: claim, lock, timeout, re-open
  - Submission Service: asset upload, validation, versioning
  - Review Service: approval workflow, revision rounds, SLA tracking
  - Payout Service: escrow, release on approval, Stripe Connect split
  - Designer Profile: portfolio, rating/tier, availability
- [ ] Brand state machine: HUMAN_FULFILLMENT path (see `docs/diagrams/03-brand-state-machine.mmd`)
  - States: AWAITING_DESIGNER → DESIGNER_ASSIGNED → UNDER_REVIEW → PUBLISHED
  - Revision loop: UNDER_REVIEW → DESIGNER_ASSIGNED
- [ ] Designer registration & onboarding UI (React SPA)
- [ ] Brief board UI: designer browsing and claiming briefs
- [ ] Submission upload UI
- [ ] Customer review UI: approve / request revision
- [ ] Stripe Connect: marketplace account setup, escrow, split payout
- [ ] Designer payout dashboard
- [ ] Designer KYC / ID verification (third-party, e.g. Stripe Identity or Onfido)
- [ ] Pricing model: decide Genesis margin (suggested 20–30% platform fee)

### Data model additions (PostgreSQL)
- `designer_profiles` (id, user_id, portfolio_url, tier, rating, verified_at)
- `briefs` (id, brand_dna_id, status, expires_at, locked_by_designer_id)
- `assignments` (id, brief_id, designer_id, accepted_at, deadline_at, withdrawn_at)
- `submissions` (id, assignment_id, version, blob_path, submitted_at)
- `reviews` (id, submission_id, status, customer_notes, reviewed_at)
- `payouts` (id, assignment_id, amount_pence, stripe_transfer_id, released_at)

### Architectural notes
- Designer Marketplace Service is a standalone microservice; it reads BrandDNA from Cosmos DB (read-only) and writes brief/assignment state to PostgreSQL.
- Plugs into the existing Brand Engine via a new fulfilment mode flag on the BrandDNA document (`fulfilmentMode: AI | HUMAN`).
- Reuses the existing Notification Service for all designer and customer alerts.
- Blob Storage path for designer uploads: `/designer-submissions/{brandId}/{assignmentId}/{version}/`
