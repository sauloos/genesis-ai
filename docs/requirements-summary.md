# Genesis Brands — Requirements Summary

> Greenfield rebuild. No legacy code carried forward.
> Stack: Java 21 + Spring Boot 3 (microservices) · React (SPA) · Kubernetes on Azure.
> Last updated: May 2026

---

## 1. Product Vision

Genesis Brands is an AI-powered brand generation platform for startups and early-stage businesses.
A user answers questions about their business in natural language; the platform generates a complete, production-ready brand identity — logo, colours, typography, copy, and a PDF brand book — in minutes.

The long-term positioning is **brand as infrastructure**: not just a one-time generation tool but a living brand operating system that evolves with the business, monitors consistency, and integrates downstream into websites, social media, and print.

**Target market:** 640K UK startups, 10M+ global new businesses per year.
**Competitors:** Looka (formerly LogoJoy), Tailor Brands.
**Differentiator:** LLM-native questionnaire, richer brand signal extraction, AI-generated copy and visual identity combined, human designer marketplace as a premium tier.

---

## 2. Pricing Tiers

| Tier | Price | Includes |
|---|---|---|
| Essential | £179 one-off | Digital brand assets (logo, colours, fonts, social templates) |
| Premium | £279 one-off | Essential + PDF brand book (brand guidelines document) |
| Ultimate | £679 one-off | Premium + printed items (business cards, stationery) |

> Recurring revenue streams (social media management, hosted website, white label) are Phase 2.

---

## 3. Functional Requirements

### 3.1 Authentication & User Management

- User registration with email and password
- Login with JWT access and refresh tokens
- Password reset via email link
- User profile management (name, email, password)
- Role-based access: `USER`, `ADMIN`, `DESIGNER` (Phase 3)
- Subscription status tracked per user
- Stripe customer created on registration

---

### 3.2 Brand Creation — Questionnaire

- LLM-driven conversational questionnaire (dynamic — number of questions adapts to signal quality)
- Questions cover: business description, target audience, personality, tone of voice, competitors, aspirations
- Each answer is processed by GPT-4o to extract structured brand signals incrementally
- Session state persisted in Redis (30-minute TTL)
- User can review and adjust extracted signals before triggering generation
- Questionnaire re-entrant — user can resume if they navigate away within the session window

---

### 3.3 Brand Generation Pipeline

The pipeline runs asynchronously via Service Bus. Copy and Visual generation run concurrently.

**Brand Engine (orchestrator)**
- Creates and owns the BrandDNA document (Cosmos DB, versioned)
- Manages the brand lifecycle state machine:
  `DRAFT → GENERATING → COMPOSING → RENDERING → READY → PUBLISHED → EVOLVING → ARCHIVED`
- Publishes events to Service Bus to trigger downstream workers
- Streams real-time progress to the client via SSE

**Copy Generator**
- Generates: tagline, brand story, mission statement, values, tone-of-voice guide, social media bio, elevator pitch, value proposition
- Model: Azure OpenAI GPT-4o
- Writes output into BrandDNA (Cosmos DB)

**Visual Generator**
- Generates: colour palette + rationale, typography selection, logo concept images, mood board
- Model: Azure OpenAI GPT-4o (reasoning) + Stability AI / Flux (image generation)
- Uploads raw images to Blob Storage
- Writes image URLs and visual metadata into BrandDNA (Cosmos DB)

**Asset Composer**
- Waits for both Copy and Visual steps to complete
- Produces: logo variants (SVG, PNG — full, icon, dark, light), social media banners, business card layout
- Uploads composed assets to Blob Storage
- Writes final asset URLs into BrandDNA (Cosmos DB)

**Delivery Service**
- Assembles brand kit: ZIP of all assets + PDF brand guidelines document
- Writes deliverable to Blob Storage
- Makes download URL available to user

---

### 3.4 Brand Identity Output

The following must be included in the generated brand kit:

| Asset | Format |
|---|---|
| Logo — full colour | SVG + PNG |
| Logo — icon only | SVG + PNG |
| Logo — dark variant | SVG + PNG |
| Logo — light/reversed variant | SVG + PNG |
| Colour palette | HEX + RGB values |
| Typography specification | Primary + secondary font, usage rules |
| Social media banner templates | LinkedIn, Instagram, Twitter/X |
| Brand copy pack | Tagline, story, bio, tone-of-voice guide |
| Brand guidelines PDF | Full document (Premium + Ultimate tiers) |
| ZIP download | All of the above bundled |

---

### 3.5 User Dashboard

- List of all brands with status indicators
- Create new brand
- View existing brand detail and assets
- Download brand kit
- Trigger brand evolution (update an existing brand)
- Order print / merch products (Phase 2)
- Account and subscription management

---

### 3.6 Notifications

All notifications sent via email (SendGrid). Triggered by Service Bus events — no direct API calls.

| Event | Email sent |
|---|---|
| User registered | Welcome email |
| Brand generation complete | "Your brand is ready" + download link |
| Order dispatched | Tracking info (Phase 2) |
| Subscription renewal | Reminder (Phase 2) |
| Brand evolved | "Your brand has been updated" |

---

### 3.7 Admin Panel

Internal tool, ADMIN role required. Not visible to customers.

- User management: list, search, suspend, delete
- Brand management: list all brands, view BrandDNA, force state transitions
- Prompt template management: edit generation prompts without redeployment
- Feature flag management (via Azure App Configuration)
- Revenue and usage metrics: MRR, generation volume, failure rates, tier breakdown

---

### 3.8 Content Management (Strapi CMS)

Manages platform content updated by non-technical team members:

- Marketing site copy (hero, features, pricing page)
- Blog articles
- Email template copy (injected by Notification Service)
- FAQ / help content

Not involved in brand generation. Strapi is for the platform's own marketing, not user-generated brand assets.

---

### 3.9 Print & Merch Orders — Phase 2

- Product catalogue: business cards, stationery, banners, branded apparel, mugs
- Order placement using generated brand assets
- Fulfilment routing to print partners (Printful, MOO, Gelato) and merch partners (Printify)
- Order status tracking and fulfilment updates
- Revenue model: margin between sale price and partner fulfilment cost
- Unlocks Ultimate tier (£679) at Phase 1 → Phase 2 transition

---

### 3.10 Brand Evolution — Phase 2

- Published brands can be updated when the business changes
- Triggers a new generation run using updated questionnaire answers
- Creates a new version of the BrandDNA (previous version preserved)
- State machine path: `PUBLISHED → EVOLVING → READY → PUBLISHED`

---

### 3.11 Designer Marketplace — Phase 3

> Build only after Phase 1 is live and generating revenue. Validated demand required before starting.

- Customers can choose "Human Fulfilment" — their BrandDNA becomes a brief for vetted designers
- Designers browse and claim briefs, upload completed assets, receive payment on approval
- Customer approves or requests revisions (limited revision rounds)
- Stripe Connect handles escrow and designer payouts (platform takes 20–30% fee)
- Designer vetting, KYC/ID verification, portfolio management
- New brand states: `AWAITING_DESIGNER → DESIGNER_ASSIGNED → UNDER_REVIEW → PUBLISHED`
- Controlled by `fulfilmentMode: AI | HUMAN` flag on BrandDNA

---

## 4. Non-Functional Requirements

### Performance
- Brand generation completed within 3 minutes end-to-end (p95)
- API response time < 200ms for non-generation endpoints (p95)
- SSE progress stream latency < 2 seconds per update
- Frontend initial load < 2 seconds on a standard broadband connection

### Availability
- Production target: 99.5% uptime
- Generation pipeline failures must be retryable (idempotent events via Service Bus)
- No data loss on pod restart (all state in external stores — Redis, Cosmos DB, PostgreSQL)

### Scalability
- Generation pods (Copy, Visual, Asset Composer) scale horizontally via HPA based on Service Bus queue depth
- Spring Cloud Gateway scales independently based on request rate
- Cosmos DB autoscale handles read/write spikes

### Security
- All secrets in Azure Key Vault — none in code or environment variables
- JWT tokens with short expiry (15 min access, 7-day refresh)
- Token blacklist in Redis for immediate revocation on logout
- WAF policy at Azure Front Door (OWASP 3.2)
- All inter-service communication within AKS (not publicly exposed)
- Pre-signed SAS URLs for brand kit downloads (15-minute expiry)

### Observability
- Structured JSON logging from all services → Azure Monitor / Log Analytics
- Distributed tracing via OpenTelemetry → Application Insights
- Alerts: generation pipeline stuck > 10 min, error rate > 2%, Redis memory > 75%

### Data
- BrandDNA documents versioned — no destructive overwrites
- Brand assets retained in Blob Storage indefinitely (move to Cool tier after 180 days)
- GDPR: user data deletion on account closure, data residency in Azure West Europe

---

## 5. Phase Summary

| Phase | Goal | Key deliverables |
|---|---|---|
| **Phase 1 — MVP** | End-to-end brand generation working for a paying customer | Auth, questionnaire, generation pipeline, brand kit delivery, React SPA, Stripe (Essential + Premium tiers) |
| **Phase 2 — Growth** | Expand revenue streams and retention | Print/merch orders, Ultimate tier, brand evolution, social media management, website hosting, white label B2B2C |
| **Phase 3 — Marketplace** | Human design premium tier | Designer marketplace, Stripe Connect payouts, designer onboarding |

---

## 6. Out of Scope — Alpha / Phase 1

The following are explicitly deferred:

- Print and merch orders (Order Service — Phase 2)
- Brand evolution (Phase 2)
- Social media management (Phase 2)
- Hosted brand website tier (Phase 2)
- White label / B2B2C portals (Phase 2)
- Designer Marketplace (Phase 3)
- Mobile app
- Multi-language support
- Team / collaborative accounts
- API access for third-party integrations

---

## 7. Key Documents

| Document | Location |
|---|---|
| Execution plan | `docs/execution-plan.md` |
| Architecture components | `docs/architecture-components.md` |
| Cost estimates | `docs/cost-estimates.md` |
| Diagram connections reference | `docs/diagram-connections.md` |
| Deployment diagram | `docs/diagrams/06-deployment.drawio` |
| Screen flow | `docs/diagrams/07-screen-flow.mmd` |
| Brand state machine | `docs/diagrams/03-brand-state-machine.mmd` |
| Designer marketplace flow | `docs/diagrams/05-designer-marketplace.mmd` |
