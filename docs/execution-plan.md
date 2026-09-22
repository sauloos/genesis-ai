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

### Admin auth & user management (backlog, added 2026-09-22)
> Platform-level, not Genesis Brands-specific — belongs in `brain-engine-core` alongside the rest
> of the admin/backoffice shell, since it applies to any tenant's admin, not just this one's.

- [ ] Proper admin login page: replace today's browser-native Basic Auth popup (single hardcoded
      `genesis.basic-auth.username`/`password` pair via `BasicAuthFilter`) with a real login form,
      consistent with the rest of the admin shell UI
- [ ] Proper admin user management: multiple named admin accounts (not one shared credential pair),
      almost certainly informed by the roles already scoped for the planned training review workflow
      (SUPER_ADMIN / TRAINER / APPROVER) rather than inventing a separate role model

> **Generation quality note — Phase 1:** Copy and Visual generators run on detailed prompt engineering
> with explicit style rules and design principles. Output quality is good but generic — the house
> style library that makes output distinctly Genesis Brands is built in Phase 2.

### Customer journey & dashboard (elaborated 2026-09-22)
> Goal: turn the client flow into a real funnel — preview before paying, a dashboard that's
> useful long after the initial three directions are delivered, not just a download page.

> **Scope note:** everything in this section — landing page, journey, dashboard, product catalog,
> consultant threads — is **Genesis Brands product surface**, not `brain-engine-core`/`genesis-os`
> platform surface. It belongs in `genesis-brands` the same way `Brand`/`Engagement`/consultant
> chat already got moved there in the recent module split. `brain-engine-core` stays the generic,
> tenant-agnostic engine (themes, training, agent orchestration primitives); it should gain no
> knowledge of directions, product catalogs, print orders, or consultant threads as this gets built.

**Funnel, in order:**
- [ ] Landing page: public marketing page, "Log in" for existing customers + "Start your brand
      journey" CTA for new ones (replaces/extends the current holding page at `/`)
- [ ] Journey → three directions preview gate: after the questionnaire, show anchored/evolved/
      disruptive directions **watermarked and low-res** before any payment — proves the value,
      withholds the deliverable. Needs a new preview-rendering step (watermark overlay +
      downscale) sitting between generation and the existing client view; current `/your-brand/{id}`
      already gates full-res PDF downloads behind `paid`, so this mostly needs a *pre-payment*
      preview variant, not a new gate mechanism
- [ ] Direction selection: customer picks one of the three directions to proceed with
- [ ] Stripe checkout: package purchase (Essential/Premium/Ultimate) at point of direction
      selection, replacing today's lead-capture + manual "mark paid"; optional **Consultant
      subscription add-on** purchased at the same step or upsold later from the dashboard
- [ ] Account creation on payment: today's lead-capture `ClientUser` becomes the full paid
      account — same login (`/login`) an existing customer uses, no separate account system

**Post-payment dashboard** (evolves `your-brand.html` into the real customer home):
- [ ] Asset library: logo + variations, brand book — download, same as today but now the
      resolved landing spot after checkout, not a standalone page
- [ ] Product catalog: purchasable physical items (business cards, stand-up banners, mugs,
      pens, etc.) **rendered live with the customer's actual brand assets** (logo/colors/type
      composited onto product mockups) with pricing — this pulls a slice of Phase 2's
      Order Service / Print & Merch Provider adapters forward into Phase 1, at least far enough
      to show a real catalog and price list; full multi-provider fulfilment logic can stay Phase 2
- [ ] Print & delivery flow: pick product + quantity, enter/confirm delivery address, place
      order — needs at minimum an Order Service and one print provider integration (Printful is
      the natural first pick per Phase 2's adapter list), even if fulfilment scope stays narrow
- [ ] Consultant panel — **scoped, not the full admin Genesis AI**: knowledge context is Layer 1
      methodology + the *one* direction this customer actually chose (not all three) + this
      customer's own generated assets. Two jobs, not one: (a) produce/revise assets, same as
      today's asset-generation dispatch, and (b) open-ended discussion — brainstorming, marketing
      strategy, "how should we position this for the holiday season" — without necessarily
      producing an asset at all. Requires a hard tenant-isolation guarantee on Layer 2 retrieval:
      this consultant must never surface another customer's precedent/engagement data, unlike the
      internal Genesis AI consultant which reasons across all of Layer 2
- [ ] Threaded conversations, not one flat history: replaces the current per-brand single
      `ConversationMessage` stream with a `ConsultantThread` concept (id, brand/engagement id,
      title, optional `linked_asset_id`). Two entry points into a thread, both landing in the same
      history: (1) general discussion started from the standalone consultant panel — may produce a
      new asset partway through, which then appears in the catalog backlinked to the thread that
      created it; (2) "Discuss / Improve" started directly from an asset in the catalog — opens or
      continues the thread already anchored to that asset, pre-loaded with its type/version/brand
      context. Dashboard needs a thread list (like an inbox), not just a single chat window
- [ ] New-asset / revision requests via consultant: whichever entry point started the thread,
      requesting something not yet generated (e.g. "a flyer for our Black Friday promo") or a
      change to an existing asset dispatches the relevant specialist agent(s) with a scoped
      direction brief (not a full 3-direction re-run) → resulting asset appears in the dashboard's
      catalog, versioned if it's a revision. Needs a narrower orchestration path than
      `EngagementOrchestratorService`'s current full-pipeline run — single-asset, single-agent
      dispatch triggered from a thread message rather than from a questionnaire submission
- [ ] Iterative asset revision: every printable/generated asset (cards, banners, flyers, mugs —
      not just net-new requests) should be revisable through consultant conversation ("make the
      flyer more urgent", "try a different color"), with the consultant re-invoking the owning
      specialist agent with feedback. Needs asset versioning (keep prior versions, not just
      overwrite) so a customer can compare/revert

**Backlog — decisions to resolve before this gets scoped into sprints** (none of these are settled
yet; each needs its own design pass, not a default picked in passing):
- [ ] Decide watermark/low-res mechanism — server-side image processing step, or a cheaper CSS
      overlay for web preview + real downscale only for anything downloadable?
- [ ] Decide subscription model shape — is "Consultant access" a recurring add-on distinct from
      the one-time brand package, and does it gate by time or by usage (message/asset count)?
- [ ] Decide product catalog mockup rendering approach — flat template compositing (logo onto a
      stock mug photo) is fast to ship; photorealistic rendering is a later upgrade, not MVP
- [ ] Decide asset revision versioning model — extend the existing Playground run-history pattern
      per agent, or a new per-customer-asset version table?
- [ ] Decide Layer 2 tenant isolation enforcement — filter at the retrieval-query level (metadata
      tag per customer/engagement) or physically separate index/namespace per customer? Filter-level
      is cheaper but a retrieval bug there becomes a real cross-customer data leak, not just a bad
      answer — this one needs a security-conscious decision, not just an engineering-convenience one
- [ ] Decide live sync behavior between an in-progress thread and the catalog — does the catalog
      panel poll (same pattern as today's engagement-status polling) while a thread's asset job
      runs, or does the thread stream progress and the catalog only refresh on completion?

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
