# Genesis Brands — Architecture Components

> This document describes every infrastructure component and microservice in the platform,
> its purpose, what it owns, and representative API endpoints or event contracts.
> See `docs/diagrams/06-deployment.drawio` for the deployment topology.

---

## Table of Contents

1. [Edge Layer](#1-edge-layer)
2. [AKS Ingress](#2-aks-ingress)
3. [Core Microservices](#3-core-microservices)
4. [Generation Pipeline](#4-generation-pipeline)
5. [Content Management](#5-content-management)
6. [Azure Managed — Data & Messaging](#6-azure-managed--data--messaging)
7. [Azure Managed — AI](#7-azure-managed--ai)
8. [Azure Managed — Ops](#8-azure-managed--ops)
9. [External SaaS](#9-external-saas)
10. [Phase 3 — Designer Marketplace](#10-phase-3--designer-marketplace)

---

## 1. Edge Layer

### Azure Front Door (Standard/Premium)

**What it is:** Microsoft's global CDN + load balancer + WAF, acting as the single public entry point for all traffic.

**Why we use it instead of separate CDN + Application Gateway:**
A single service handles static asset delivery, API routing, DDoS protection, and WAF policy. No split configuration, one SSL certificate, one DNS entry, one place to tune caching rules.

**What it does:**

| Traffic type | Rule | Routed to |
|---|---|---|
| `/* static` (JS, CSS, fonts, images) | Cached at global PoPs | Blob Storage origin |
| `/api/*` | Not cached, forwarded | AKS NGINX Ingress |
| `/ws/*` (WebSocket / SSE) | Not cached, forwarded | AKS NGINX Ingress |
| Health probes | Continuous | AKS Ingress |

**Key features used:**
- WAF policy (OWASP 3.2 ruleset) on the Premium tier
- Origin groups with health probes and automatic failover
- Custom caching rules: `/assets/*` cached for 30 days; `/api/*` never cached
- Rate limiting at the edge (backs up rate limiting in Spring Cloud Gateway)

---

## 2. AKS Ingress

### NGINX Ingress Controller

**Namespace:** `ingress`

**What it is:** The Kubernetes-native HTTP reverse proxy running inside the cluster. Front Door terminates TLS at the edge; NGINX handles internal routing to the correct service.

**What it does:**
- Routes by path and hostname to the correct Kubernetes service
- Handles TLS between Front Door and the cluster (mTLS option)
- Provides `/healthz` and `/readyz` probe endpoints consumed by Front Door

**Example ingress rules:**
```
/api/v1/brands      → brand-engine-svc:8080
/api/v1/orders      → order-svc:8080
/api/v1/identity    → identity-svc:8080
/ws/*               → gateway-svc:8080 (WebSocket upgrade)
```

---

### Spring Cloud Gateway

**Namespace:** `ingress`  
**Deployment:** Kubernetes Deployment with HPA (scales 2–8 pods on CPU/RPS)

**What it is:** The API gateway — all client-facing API calls pass through here before reaching a microservice. It is the only service that faces NGINX directly.

**What it does:**
- JWT validation (delegated to Identity Service via token introspection)
- Rate limiting per user/IP (backed by Redis)
- Request routing to downstream microservices
- WebSocket and SSE proxying for real-time generation progress
- Response header normalisation and CORS

**Why Spring Cloud Gateway and not NGINX for this:**
NGINX doesn't natively integrate with Spring Security, Redis rate limiting, or service discovery. Gateway is code — we can add custom filters (e.g. inject the resolved `tenantId` header for every downstream call).

**Example routes configured:**
```
POST   /api/v1/auth/**          → identity-svc
GET    /api/v1/brands/**        → brand-engine-svc
POST   /api/v1/brands/**        → brand-engine-svc
GET    /api/v1/orders/**        → order-svc
POST   /api/v1/orders/**        → order-svc
GET    /api/v1/admin/**         → admin-svc  (role: ADMIN required)
GET    /sse/brands/{id}/stream  → brand-engine-svc (SSE)
```

---

## 3. Core Microservices

All core services are Java 21 + Spring Boot 3.x, containerised, deployed as Kubernetes Deployments in the `core` namespace.

---

### Identity Service

**Responsibility:** Authentication, authorisation, user account lifecycle.

**Owns:**
- User records (PostgreSQL)
- OAuth2 / OIDC token issuance (Spring Authorization Server)
- Role definitions: `USER`, `ADMIN`, `DESIGNER` (Phase 3)
- Stripe customer ID mapping

**Integrates with:**
- PostgreSQL (user store)
- Stripe (create/retrieve customer on registration)
- Redis (token blacklist for logout / revoke)

**Example endpoints:**
```
POST   /api/v1/auth/register          Register a new user account
POST   /api/v1/auth/login             Issue JWT access + refresh tokens
POST   /api/v1/auth/refresh           Rotate refresh token
POST   /api/v1/auth/logout            Blacklist current token
GET    /api/v1/users/me               Current user profile
PATCH  /api/v1/users/me               Update profile (name, email, password)
GET    /api/v1/users/me/subscription  Current subscription status
POST   /api/v1/auth/introspect        Internal — token validation for Gateway
```

---

### Brand Engine (Orchestrator)

**Responsibility:** Central orchestrator for all brand creation and evolution. Owns the BrandDNA document lifecycle and coordinates the async generation pipeline.

**Owns:**
- BrandDNA documents (Cosmos DB) — versioned, the master brand record
- State machine transitions: `DRAFT → GENERATING → COMPOSING → RENDERING → READY → PUBLISHED → EVOLVING → ARCHIVED`
- Generation job tracking

**Integrates with:**
- Cosmos DB (read/write BrandDNA)
- Service Bus (publishes `brand.generation.started`, `brand.step.completed`, etc.)
- Conversation Service (delegates questionnaire flow)
- SSE endpoint for real-time progress streaming to the client

**Example endpoints:**
```
POST   /api/v1/brands                   Start a new brand (creates DRAFT BrandDNA)
GET    /api/v1/brands/{id}              Get brand + current state
GET    /api/v1/brands/{id}/versions     List all versions of a brand
GET    /api/v1/brands/{id}/dna          Full BrandDNA document
PATCH  /api/v1/brands/{id}/publish      Transition READY → PUBLISHED
POST   /api/v1/brands/{id}/evolve       Trigger a brand evolution (PUBLISHED → EVOLVING)
DELETE /api/v1/brands/{id}              Archive brand (soft delete)
GET    /sse/brands/{id}/stream          SSE stream — real-time generation progress events
```

**Key events published to Service Bus:**
```
brand.generation.started      { brandId, tenantId, triggeredAt }
brand.copy.requested          { brandId, dnaSnapshot }
brand.visual.requested        { brandId, dnaSnapshot }
brand.step.completed          { brandId, step, resultRef }
brand.ready                   { brandId }
```

---

### Conversation Service

**Responsibility:** Manages the LLM-powered questionnaire that extracts brand signals from the user. Turns natural language answers into structured BrandDNA fields.

**Owns:**
- Conversation session state (Redis — ephemeral)
- Prompt templates for questionnaire turns
- Signal extraction logic (calls Azure OpenAI)

**Flow:**
1. Brand Engine starts a brand → triggers a conversation session
2. Client sends user answers turn by turn via the Conversation Service
3. After enough signal is gathered, the service writes extracted fields back into the BrandDNA and notifies Brand Engine to proceed

**Example endpoints:**
```
POST   /api/v1/conversations                     Start a conversation session for a brand
GET    /api/v1/conversations/{sessionId}          Get current session state + next question
POST   /api/v1/conversations/{sessionId}/answer   Submit an answer, receive next question
POST   /api/v1/conversations/{sessionId}/complete  Force-complete (skip remaining questions)
GET    /api/v1/conversations/{sessionId}/signals   Extracted BrandDNA signals so far
```

---

### Order Service

**Responsibility:** Manages print and merch product orders. Handles pricing, order creation, fulfilment dispatch to print partners, and order status tracking.

**Owns:**
- Orders (PostgreSQL)
- Product catalogue (PostgreSQL) — SKUs, prices, partner mappings
- Fulfilment routing logic (which order goes to which partner)

**Integrates with:**
- PostgreSQL (orders, products)
- Printful, MOO, Gelato (print fulfilment via HTTP)
- Printify (merch fulfilment via HTTP)
- Stripe (payment intent creation — payment itself handled by Identity Service / Stripe)

**Example endpoints:**
```
GET    /api/v1/products                        Browse available print/merch products
GET    /api/v1/products/{sku}                  Product detail + pricing
POST   /api/v1/orders                          Place an order (requires brandId + SKU list)
GET    /api/v1/orders/{id}                     Order status + tracking
GET    /api/v1/orders                          Order history for current user
POST   /api/v1/orders/{id}/cancel              Cancel if not yet dispatched
GET    /api/v1/orders/{id}/fulfilment          Fulfilment partner status + tracking URL
```

---

### Delivery Service

**Responsibility:** Assembles the final brand deliverable — downloads all generated assets from generation pods, packages them into a brand kit (ZIP + PDF), and writes the result to Blob Storage.

**Owns:**
- Brand kit assembly logic
- PDF generation (brand guidelines document)
- Blob Storage write operations for deliverables

**Triggered by:** Service Bus event `brand.ready` — not called directly by clients.

**Example endpoints:**
```
GET    /api/v1/brands/{id}/download           Download URL for assembled brand kit (ZIP)
GET    /api/v1/brands/{id}/assets             List individual asset URLs (logo, colours, fonts)
GET    /api/v1/brands/{id}/guidelines         Download brand guidelines PDF
```

---

### Notification Service

**Responsibility:** Sends transactional emails and (future) push notifications for key platform events.

**Owns:**
- Email template registry (references Strapi for template content)
- Notification delivery log (PostgreSQL — for audit and dedup)

**Triggered by:** Service Bus events — does not expose a public API.

**Events consumed:**
```
brand.ready                → "Your brand is ready!" email + download link
order.dispatched           → "Your order is on its way" email + tracking
user.registered            → Welcome email
brand.evolving.complete    → "Your brand has been updated" email
```

**Integrates with:**
- SendGrid (email delivery)
- PostgreSQL (delivery log)

---

### Admin Service

**Responsibility:** Internal back-office operations. Not exposed to end users — only accessible with `ADMIN` role, routed via the Gateway to a restricted path.

**Owns:**
- Platform-level user management
- Feature flag management (writes to App Configuration)
- Revenue and usage reporting queries
- Manual state overrides for brands stuck in bad states

**Example endpoints:**
```
GET    /api/v1/admin/users                         List all users with filters
PATCH  /api/v1/admin/users/{id}/suspend            Suspend a user account
GET    /api/v1/admin/brands                        List all brands + states
PATCH  /api/v1/admin/brands/{id}/state             Force a state transition
GET    /api/v1/admin/metrics/revenue               Revenue summary (MRR, ARR, per stream)
GET    /api/v1/admin/metrics/generation            Generation volume, failure rates, latency
POST   /api/v1/admin/flags/{key}                   Set a feature flag value
```

---

## 4. Generation Pipeline

Services in the `generation` namespace consume events from Service Bus and produce AI-generated content. They are stateless workers — scaled by HPA based on Service Bus queue depth.

---

### Copy Generator

**Responsibility:** Generates all brand text: taglines, brand story, tone-of-voice guide, social media bio, elevator pitch, and value proposition.

**Triggered by:** `brand.copy.requested` event from Service Bus.

**What it does:**
1. Reads BrandDNA signals from the event payload
2. Constructs a prompt chain against Azure OpenAI (GPT-4o)
3. Writes generated copy back to the BrandDNA document in Cosmos DB
4. Publishes `brand.step.completed { step: "COPY" }` to Service Bus

**No public HTTP endpoints** — pure event consumer/producer.

---

### Visual Generator

**Responsibility:** Generates the visual identity: colour palette rationale, typography selection, logo concept prompts, and image generation for mood boards and logo variants.

**Triggered by:** `brand.visual.requested` event from Service Bus. Runs concurrently with Copy Generator.

**What it does:**
1. Calls Azure OpenAI (GPT-4o) for colour palette reasoning and typography recommendations
2. Calls Stability AI or Flux API for logo concept images and mood board images
3. Uploads generated images to Blob Storage
4. Writes visual asset references + rationale back to BrandDNA in Cosmos DB
5. Publishes `brand.step.completed { step: "VISUAL" }`

---

### Asset Composer

**Responsibility:** Takes the raw outputs from Copy Generator and Visual Generator and composes polished, production-ready assets: logo files (SVG/PNG), social media templates, business card layouts.

**Triggered by:** Both `brand.step.completed { step: "COPY" }` and `brand.step.completed { step: "VISUAL" }` — waits for both before starting.

**What it does:**
1. Applies the colour palette and typography to branded templates
2. Generates SVG/PNG logo variants (full, icon, dark, light)
3. Creates social media banner templates (LinkedIn, Instagram, Twitter)
4. Uploads all assets to Blob Storage under `/{tenantId}/brands/{brandId}/assets/`
5. Publishes `brand.step.completed { step: "ASSETS" }`

---

## 5. Content Management

### Strapi CMS

**Namespace:** `cms`

**What it is:** Open-source headless CMS used for marketing copy, blog posts, and email templates. Managed separately from the product microservices so that non-technical team members can update content without a deployment.

**What it owns:**
- Marketing site copy (hero text, feature descriptions, pricing page)
- Blog articles
- Email template content (injected by Notification Service)
- Help/FAQ content

**Accessed by:**
- The React frontend (direct Strapi REST/GraphQL API calls for marketing pages)
- Notification Service (fetches email templates at send time)
- Spring Cloud Gateway routes `/cms/*` to Strapi

**Example Strapi content types:**
```
GET    /api/landing-pages/{slug}         Marketing page content
GET    /api/blog-posts?populate=*        Blog article list
GET    /api/email-templates/{key}        Email template by key (e.g. "brand-ready")
GET    /api/pricing-tiers               Pricing display content
```

---

## 6. Azure Managed — Data & Messaging

### Cosmos DB

**What it is:** Microsoft's globally-distributed NoSQL document database.

**What we store:**
- BrandDNA documents — the core domain object. One document per brand, versioned.
- Each document is a rich JSON structure containing all brand signals, generated content, asset references, and state history.

**Why Cosmos DB and not PostgreSQL for this:**
- BrandDNA is a rich, evolving document — the schema changes across product versions and between brands (a fashion brand's DNA looks very different to a SaaS brand's DNA).
- Versioning is built into the document model — no migration headaches when we add new brand signals.
- Point-in-time reads of any previous brand version.

**Example BrandDNA document structure:**
```json
{
  "id": "brand_01HXYZ",
  "tenantId": "user_01HABC",
  "version": 3,
  "state": "PUBLISHED",
  "signals": {
    "industry": "fintech",
    "audience": "early-stage founders",
    "personality": ["trustworthy", "bold", "approachable"],
    "competitors": ["Stripe", "Mercury"]
  },
  "copy": {
    "tagline": "Banking built for builders.",
    "brandStory": "...",
    "toneOfVoice": { "formal": 0.3, "playful": 0.6, "bold": 0.8 }
  },
  "visual": {
    "palette": [{ "name": "Midnight", "hex": "#0A0A23" }],
    "typography": { "primary": "Inter", "secondary": "Playfair Display" },
    "logoAssets": { "svg": "https://cdn.../logo.svg" }
  },
  "createdAt": "2026-04-22T10:00:00Z",
  "updatedAt": "2026-04-22T10:14:00Z"
}
```

---

### PostgreSQL Flexible Server

**What it is:** Managed relational database for all structured, relational data.

**What we store:**
- Users, roles, sessions (Identity Service)
- Orders, order items, fulfilment records (Order Service)
- Billing records, subscription tiers (Identity Service)
- Notification delivery log (Notification Service)
- Admin audit log (Admin Service)
- Designer records, marketplace transactions (Phase 3)

**Why PostgreSQL and not Cosmos DB for this:**
Orders, billing, and user records have hard relational integrity requirements (an order must have a valid user, a line item must reference a valid product SKU). Relational constraints + ACID transactions are the right tool.

---

### Blob Storage

**What it is:** Azure's object storage — equivalent to AWS S3.

**What we store:**

| Path pattern | Contents |
|---|---|
| `/{tenantId}/brands/{brandId}/assets/` | Logo SVG/PNG variants, mood board images |
| `/{tenantId}/brands/{brandId}/kits/` | Assembled brand kit ZIPs and PDF guidelines |
| `/templates/` | Base asset templates used by Asset Composer |
| `/cms/` | Strapi media uploads |

**Access pattern:**
- Brand assets are served via Azure Front Door (CDN-cached, long TTL)
- Download URLs are pre-signed SAS URLs with short expiry (15 min) for kit downloads
- Asset Composer and Delivery Service write directly via the Azure SDK with managed identity

---

### Service Bus

**What it is:** Azure's managed message broker — equivalent to AWS SQS/SNS. The backbone of the async brand generation pipeline.

**Why async:**
Brand generation takes 30–120 seconds end-to-end. Making the client wait synchronously for that is a terrible UX. Instead: the client fires a request, gets back a `202 Accepted` + a brand ID, then subscribes to an SSE stream for progress. The pipeline runs entirely via events.

**Topics and subscriptions:**

| Topic | Published by | Consumed by |
|---|---|---|
| `brand-lifecycle` | Brand Engine | Copy Generator, Visual Generator, Asset Composer, Delivery, Notification |
| `order-events` | Order Service | Notification Service |
| `user-events` | Identity Service | Notification Service |

**Why Service Bus over Kafka:**
At this scale, Service Bus Standard tier is more than sufficient and has zero operational overhead. We're not doing event streaming or replay at launch. Re-evaluate when generation volume exceeds ~50K/month.

---

### Redis Cache

**What it is:** Managed in-memory key/value store.

**What we use it for:**

| Use case | Service | TTL |
|---|---|---|
| JWT token blacklist (logout/revoke) | Identity Service | Until token expiry |
| Rate limit counters | Spring Cloud Gateway | Per window (60s) |
| Conversation session state | Conversation Service | 30 minutes |
| BrandDNA read cache (hot brands) | Brand Engine | 5 minutes |

---

## 7. Azure Managed — AI

### Azure OpenAI

**What it is:** Microsoft's managed deployment of OpenAI models inside the Azure trust boundary. Data does not leave Azure; no OpenAI training on our prompts.

**Model used:** GPT-4o (latest stable deployment)

**Why Azure OpenAI over OpenAI directly:**
- Data sovereignty — brand signals and user inputs stay in Azure West Europe
- Predictable latency SLA tied to Azure regions
- Quota is pre-allocated rather than shared pool
- Unified billing and compliance posture

**Consumed by:**
- Conversation Service (signal extraction from questionnaire)
- Copy Generator (all brand copy)
- Visual Generator (colour and typography reasoning, image prompts)

---

## 8. Azure Managed — Ops

### App Configuration

**What it is:** Centralised key/value store for runtime configuration and feature flags.

**What we store:**
- Feature flags (e.g. `feature.designer-marketplace.enabled = false`)
- A/B test variant assignments
- Runtime tuning parameters (e.g. `generation.timeout.seconds = 120`)
- Environment-specific config overrides

**Consumed by:** Spring Cloud Gateway (routing rules), Admin Service (flag writes), all services via Spring Cloud Azure Config starter.

---

### Key Vault

**What it is:** Managed secrets store — no secrets in code, no secrets in environment variables.

**What we store:**
- Azure OpenAI API keys
- Stripe API keys (secret + webhook signing keys)
- SendGrid API key
- Stability AI API key
- Database connection strings
- Internal service-to-service shared secrets

**How it reaches pods:** The CSI Secret Store driver syncs Key Vault secrets into Kubernetes pod volumes at startup. Services read secrets as environment variables or mounted files — no SDK calls at runtime.

---

### Container Registry (ACR)

**What it is:** Private Docker image registry within Azure.

**What we store:** Built images for every microservice, tagged by Git commit SHA and semantic version.

**How it's used:** AKS nodes pull images from ACR at pod startup using managed identity — no credentials needed.

---

### Azure Monitor

**What it is:** Centralised observability — logs, metrics, distributed tracing, and alerting.

**What we send:**
- **Logs:** Structured JSON logs from all services (via Spring Boot Logback → Log Analytics workspace)
- **Metrics:** JVM metrics, HTTP request rates, queue depths (via Micrometer → Azure Monitor)
- **Traces:** Distributed request traces across services (via OpenTelemetry → Application Insights)

**Key alerts configured:**
- Generation pipeline stuck (no `brand.ready` within 10 minutes of start)
- Error rate >2% on any service over a 5-minute window
- Cosmos DB RU consumption >80% of provisioned limit
- Redis memory >75%

---

## 9. External SaaS

### Stripe

**Purpose:** Customer payment processing — subscription billing and one-off product purchases.

**Used by:** Identity Service (subscription management), Order Service (payment intents for print/merch orders)

**Key operations:**
- Create customer on registration
- Create subscription on plan selection
- Webhook handler for `invoice.paid`, `customer.subscription.deleted`, `payment_intent.succeeded`

---

### SendGrid

**Purpose:** Transactional email delivery.

**Used by:** Notification Service exclusively.

**Key email types:** Welcome, brand ready, order dispatched, subscription renewal reminder, password reset.

---

### Stability AI / Flux

**Purpose:** Image generation for logo concepts and mood board imagery.

**Used by:** Visual Generator (generation namespace).

**Why external and not Azure OpenAI DALL-E:**
Stability AI and Flux models currently produce better results for brand/logo-style imagery at lower cost per image. This is plugged in behind the Provider Abstraction Layer — if Azure DALL-E improves or becomes cheaper, we swap the adapter without touching Visual Generator business logic.

---

### Print Partners — Printful, MOO, Gelato

**Purpose:** On-demand printing and fulfilment for brand merchandise (business cards, stationery, packaging, banners).

**Used by:** Order Service.

**How it works:** Order Service calls the partner's API with the brand asset URLs and product SKU. The partner prints and ships directly to the customer. We earn the margin between our sale price and the partner's fulfilment cost.

---

### Merch Partners — Printify

**Purpose:** On-demand merch fulfilment (branded apparel, mugs, tote bags, etc.).

**Used by:** Order Service. Same margin model as print partners.

---

## 10. Phase 3 — Designer Marketplace

> Not built at launch. Infrastructure is provisioned (namespace exists, feature flag is `false`) but the service is not deployed until Phase 1 and 2 are live and validated.

### Designer Marketplace Service

**Namespace:** `marketplace`

**Responsibility:** Connects customers who want human-designed brand fulfilment with vetted freelance designers. When a customer selects "Human Fulfilment", the BrandDNA document becomes a structured brief for designers to bid on and execute.

**Owns:**
- Designer profiles and vetting status (PostgreSQL)
- Project briefs (derived from BrandDNA — read-only access to Cosmos DB)
- Bid and assignment records (PostgreSQL)
- Revision request workflow
- Escrow and payout records (via Stripe Connect)

**New BrandDNA states added for this flow:**
```
AWAITING_DESIGNER → DESIGNER_ASSIGNED → UNDER_REVIEW → (revision loop) → PUBLISHED
```

**Integrates with:**
- Cosmos DB (read-only — reads BrandDNA as brief)
- PostgreSQL (designer and project data)
- Blob Storage (designer portfolio assets, submission uploads)
- Stripe Connect (escrow, split payments, designer payouts)

**Example endpoints:**
```
GET    /api/v1/marketplace/designers              Browse vetted designers
GET    /api/v1/marketplace/designers/{id}         Designer profile + portfolio
POST   /api/v1/marketplace/projects               Submit a brand for human fulfilment
GET    /api/v1/marketplace/projects/{id}          Project status + current designer
POST   /api/v1/marketplace/projects/{id}/approve  Customer approves submission → triggers payout
POST   /api/v1/marketplace/projects/{id}/revise   Request a revision (limited rounds)

--- Designer-facing ---
GET    /api/v1/marketplace/briefs                 Available briefs to bid on
POST   /api/v1/marketplace/briefs/{id}/bid        Submit a bid
POST   /api/v1/marketplace/projects/{id}/submit   Submit completed work
```

**Stripe Connect role:**
When a customer approves a submission, the Order Service releases the escrowed payment. Stripe Connect splits it: designer payout (e.g. 80%) + platform fee (20%), settled directly to the designer's connected Stripe account.
