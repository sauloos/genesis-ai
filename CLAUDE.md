# Genesis AI — Claude Code Context

## What this project is

Genesis AI is an AI-powered brand intelligence system. It acts as a creative director:
conducting client discovery, orchestrating specialist agents, evaluating their output
against the agency's methodology, and assembling complete brand deliverables.

Every engagement produces three brand directions — anchored, evolved, and disruptive.

**Reference architecture:** `docs/genesis-ai-architecture.md`

---

## The Two-Layer Knowledge Base

### Layer 1 — Reasoning Substrate
Two sub-layers: **1a — Knowledge Sources** (raw corpus: blog posts, podcast transcripts,
books, courses, workshop materials — the philosophy behind the agency's approach) and
**1b — Reasoning Modules** (structured YAML rules distilled from those sources, applied
at runtime). Sources are not queried directly; they inform the modules.

```
knowledge/
  layer1/
    sources/         raw corpus — blog posts, podcast transcripts, book highlights,
                     course notes, workshop materials
    modules/
      intake/        brief completeness criteria, probing question frameworks
      directions/    brand archetype frameworks, direction brief templates
      evaluation/    copy, logo, and visual quality rubrics
      assembly/      playbook and brand book structure
```

### Layer 2 — Project Memory
Every completed client engagement — briefs, reasoning traces, deliverables, client
selections. Continuously ingested. Queried by semantic similarity + metadata filters.

```
knowledge/
  layer2/            ingested project records (via KnowledgeService)
  assets/            logos, brand books, campaign imagery (local → Azure Blob)
```

Raw content (Layer 1 sources, training sessions, client records, assets) lives in
Azure Blob Storage in production — not in this repo. GitHub holds code only.
Qdrant is the vector store; PostgreSQL holds structured records and metadata.

---

## Genesis AI — Orchestrator Agent

**Model:** Claude Opus 4 with extended thinking

Genesis AI operates in two modes — same model, same knowledge base, same brand context:

**Mode 1 — Engagement (background orchestrator):**
1. Conduct client journey (structured questionnaire + conversational extension)
2. Decide when brief is complete (Layer 1 completeness criteria)
3. Derive three creative directions from brief + Layer 1 + Layer 2 precedent
4. Dispatch to specialist agents with direction briefs
5. Evaluate specialist output against Layer 1 rubrics — accept or loop
6. Instruct assembly of playbook and brand book
7. Trigger Layer 2 ingestion on completion

**Mode 2 — Consultant (conversational interface):**
A persistent chat interface where the user consults Genesis AI as a creative director.
Genesis AI has full context (BrandDNA + Layer 1 + Layer 2) and responds with strategic
advice, answers brand questions, explains its reasoning, and can propose actions (e.g.
trigger a new engagement, suggest BrandDNA updates). Conversation history is persisted
per brand. Responses stream in real time.

---

## Specialist Agents

**Model:** Claude Sonnet 4.6

| Agent | Produces |
|---|---|
| Copy Agent | Tagline, mission, brand story, elevator pitch, tone guide |
| Visual Identity Agent | Colour palette, typography |
| Logo Agent | Logo mark concept |
| Playbook Assembly Agent | Brand playbook document |
| Brand Book Assembly Agent | Brand book PDF |

Each runs within a Genesis AI evaluation loop.

---

## Tech Stack

| Layer | Dev | Production |
|---|---|---|
| Orchestrator | Claude Opus 4 (Anthropic SDK) | Claude Opus 4 |
| Specialists | Claude Sonnet 4.6 | Claude Sonnet 4.6 |
| Layer 1 store | YAML files in this repo | Azure Blob Storage |
| Layer 2 vector store | Qdrant (local) | Qdrant Cloud |
| Structured data | PostgreSQL (local) | Azure Database for PostgreSQL |
| Raw content / assets | `knowledge/` local | Azure Blob Storage |
| Embeddings | OpenAI text-embedding-3-small | OpenAI / Azure OpenAI |
| API layer | TBD | TBD |
| Frontend | TBD | TBD |

---

## Client-Facing Widget Architecture

**Every customer-facing UI element is a widget, and every widget is its own
independently developed, pluggable component.** This is a standing architectural
principle, not a per-widget decision — it applies to all current and future widget
types (questionnaire, login, logout, productOptions, payment, brandResults, content,
and any tenant-custom widget added later).

A widget bundles everything it needs to run — UI, logic, its simulate-mode behavior,
and its config/outcome model — and is loaded dynamically by the app, never hardcoded
inline in a page:

- **Server-side metadata** — a plain `@Component` bean implementing `WidgetDescriptor`
  (`platform/WidgetDescriptor.java`): `widgetType()`, `displayName()`, `configOptions()`,
  `outcomes()`. Auto-collected, no registry wiring, no hardcoded type list anywhere —
  mirrors `CoreAgent`.
- **Client-side implementation** — a static module at `static/widgets/<widgetType>/widget.js`
  (+ optional `widget.css`), resolved purely by the `widgetType` string and loaded via
  `static/widgets/loader.js`'s dynamic `import()`. Contract:
  `export function mount(container, widget, ctx)` where `widget = { id, slotKey,
  orderInSlot, widgetType, config }` and `ctx = { simulate?, onOutcome?(key?) }`.

Adding a new widget — including a tenant's own custom widget — means dropping one
`@Component` bean plus one static JS module at the conventional path. It never requires
editing a host page (`flow-runtime.html`, `login.html`, etc.) or any core dispatch table.
A widget consumed from multiple places (in-flow, a standalone page, an admin preview)
uses the *same* module every time — never a page-specific reimplementation. `login` is
the reference implementation of this pattern (`static/widgets/login/`); the remaining
widget types listed above still live inline in `flow-runtime.html` and are pending the
same extraction.

---

## Agent Pluggable Architecture

**Agents are pluggable components, exactly like widgets.** This is a standing architectural
principle, not a per-agent decision — it applies to every current and future agent, including
ones not yet designed (e.g. flyer, banner, signage, standup-banner, or any other asset-type
agent), whether built inside this application or shipped as its own module.

An agent bundles three things, and is added to the app with zero core wiring — `CoreAgent`
beans are already auto-collected via Spring component scan, same as `WidgetDescriptor`:

- **Its full definition** — config (model/maxTokens in `application.yml`), system prompt, and
  logic (the `*Agent.java` / `*RefinementLoop.java` classes).
- **Its metadata** — `agentId`, `displayName`, `description`, `icon`.
- **Its own screens** — a Playground view and a Live Dashboard view, each a self-contained
  client-side module resolved by convention from `agentId` (`static/agent-views/<agentId>/
  playground.js` and `.../live.js`), exactly like a widget's `widgetType` resolves to
  `/widgets/<type>/widget.js` via `loader.js`. Resolution is convention-based — no registry.

**An agent's module placement follows what it is, not where it's wired in** — same rule as
widgets, and already reflected in the codebase today: **Consultant is a Genesis OS (core)
agent** (`brain-engine-core/.../agent/consultant/ConsultantCoreAgent.java`) because every
tenant gets a consultant — it's platform-level, sector-agnostic capability. **Copy, Visual
Identity, Logo, Playbook, and Brand Book are Genesis Brands (tenant) agents** (`genesis-brands/
.../agent/{copy,visualidentity,logo,playbook,brandbook}/...CoreAgent.java`) because they are
specific to the brand-engagement product this tenant sells — a different tenant could ship a
completely different set of SLOT/CREATE agents. This split is invisible to the pluggable
mechanism itself: `AgentCatalogService` (core) auto-collects every `CoreAgent` bean via Spring
component scan regardless of which module declared it, and `dashboardAgents`/Playground treat
core and tenant agents identically — one catalog, one card list, one dialog/loader convention.
When adding a future agent (or auditing an existing one), always ask "does every tenant need
this, or is this specific to Genesis Brands' product" before picking its module — never default
to genesis-brands out of convenience.

**Enablement is per-surface and admin-configurable** — `AgentCatalogConfig.availableForPlayground`
/ `availableForLiveView` (configured via the agents admin) gate whether an agent's card appears
on each surface at all. This is orthogonal to whether the agent *has* a view module for that
surface (declared via `CoreAgent.hasLiveView()` — no runtime probing).

**The host surface is always pure chrome.** On Playground's right-hand panel, or in a dialog
popped up on `/live/dashboard`, selecting an agent loads and renders **that agent's own view
module** into the panel/dialog. The host (e.g. the `dashboardAgents` widget) only lists cards
and provides the panel/dialog shell — it never owns agent-specific form fields, output
rendering, or submit logic. Whatever a Live Dashboard view generates lands back in the Assets
widget.

**The `/live/dashboard` chrome widgets (`dashboardAgents`, `dashboardAssets`, and the
not-yet-built `catalog` widget) are core (Genesis OS), not tenant.** Same reasoning as the
agents themselves: every tenant wants a card grid of available agents and a list of a client's
generated assets — the mechanism is platform-level. What's tenant-specific is the data behind
it (Genesis Brands' `Engagement`/`resultsJson`). A widget here must not hardcode tenant REST
endpoints (`/api/engagements/...`) directly into its client JS — it calls a core-owned
generic endpoint/SPI that a tenant implements, the same way `ConsultantSubjectProvider` and
`ConsultantToolProvider` do for Consultant (see below). **Known gap, not yet fixed:**
`DashboardAgentsWidget`/`DashboardAssetsWidget` (both the `WidgetDescriptor` bean and the
`widget.js`) currently live in `genesis-brands` and the JS calls `/api/engagements/mine` and
`/api/engagements/{id}/preview/{direction}` directly — this needs the same core-interface/
tenant-implementation split as Consultant before it's truly core, not just a file move to
brain-engine-core.

**Two output modes** — declared per agent, because they're stored differently, not because
either is less "pluggable":
- **SLOT** — one canonical output per `(engagementId, direction, agentId)`, overwritten in
  place. The 5 current specialists (Copy, Visual Identity, Logo, Playbook, Brand Book) are
  SLOT agents, and are cascade-coupled (regenerating Copy/Visual Identity/Logo requires
  re-running Playbook and Brand Book downstream, and re-rendering the PDF/logo ZIP).
- **CREATE** — each invocation appends a new asset instance rather than overwriting a slot
  (e.g. a future flyer/banner/signage/standup-banner agent — a client may want several). A
  CREATE agent reads other agents' SLOT output as brief context but is not cascade-coupled to
  them.

**Playground vs. Live Dashboard scope differs for SLOT agents, deliberately.** Playground has
no owning `Engagement`, so its view still needs brief/questionnaire controls. The Live
Dashboard surface always has an owning, already-completed `Engagement` to fall back on, so a
SLOT agent's Live Dashboard view exposes *only* a regenerate command (optionally with
feedback) — never a questionnaire/brief form; the brief was already derived once.

**Consultant has two contexts, not one:**
- **Admin/Playground Consultant is the "pure consultant"** — no customer context at all
  (today's standalone `Brand` sandbox via `BrandConsultantSubjectProvider`).
- **The customer-logged-in Consultant chat must be fully customer-aware** — grounded in that
  one client's own brief, tone/language, logo, palette, and other generated assets — and able
  to invoke other specialist agents on the client's behalf using that context as the implicit
  brief.
- **Brand context is always derived server-side from the database, per request — never from
  the session or anything the client supplies.** The session carries only identity
  (`clientUserId`); a context-resolution service loads that client's own `PAID`/`DONE`
  `Engagement`, parses `resultsJson`, and reduces it to a compact context DTO injected into the
  system prompt only for the customer-aware chat origin. This is a security boundary, not just
  a design preference: trusting client-supplied brand context would let one client's chat
  produce output grounded in another client's brand. The Layer 2 vector store (above) is the
  wrong source for this — it ingests only on engagement completion and exists for
  cross-engagement precedent, not this-client-right-now freshness.
- **The Consultant's tool roster (which specialist agents it can invoke) is enumerated live
  from the agent catalog, never hardcoded** — so adding a new pluggable agent (any SLOT or
  CREATE agent) automatically becomes available to the Consultant with zero Consultant-side
  code changes.

**Module placement for Consultant follows a strict fetch/provide split — this is the template
for every future Consultant capability, not just brand context.** Consultant is a core agent,
so the *mechanics* always live in brain-engine-core: the act of fetching context, the act of
deciding when/whether to attach tools to the Prompt, the generic shape of "a subject" and "a
tool." What a tenant supplies is the *content* behind those mechanics, through a core-defined
interface it implements — never by core reaching into a tenant model directly:
- **Fetching context is core; providing it is tenant.** `ConsultantSubjectProvider` (core
  interface, brain-engine-core) is what `ConsultantService` calls to fetch a subject's context —
  core owns retrieval, history, and streaming. `BrandConsultantSubjectProvider` (genesis-brands)
  is the implementation that actually knows about `Engagement`/`resultsJson`/`Brand` and
  produces the context DTO — that domain knowledge never belongs in brain-engine-core.
- **The skill/tool-calling *pattern* is core; the specific skill mappings are tenant (or
  whichever module owns the agent).** `ConsultantToolProvider` (core interface) is what lets
  *any* module — tenant or future core utility agents alike — register Spring AI tools onto a
  CUSTOMER-sourced chat; core only owns when tools get attached, never which agents exist or
  what they do. `ConsultantRegenerationToolProvider` (genesis-brands) is the concrete mapping
  of "regenerate_logo" → the Logo agent, "regenerate_copy" → the Copy agent, etc. — swapping in
  a different tenant's agents means writing a new `ConsultantToolProvider`, with zero changes to
  `ConsultantService` or the core tool-attachment mechanics.
- Apply this same split to every future Consultant-adjacent capability (e.g. a future
  "Consultant can browse version history" tool): define the generic interface in
  brain-engine-core first, implement the tenant-specific content in genesis-brands second —
  never let a convenience shortcut put tenant model types (`Engagement`, `Brand`, etc.) inside
  brain-engine-core code.

`login` is the reference implementation of the equivalent widget pattern; no agent has yet
been fully migrated to this pattern end-to-end (Playground's current per-agent UI is generic/
DirectionBrief-form-based rather than per-agent-view-module-based) — this section is the
target state every agent, current or future, must be checked against.

---

## Key documents

| Document | What it covers |
|---|---|
| `docs/genesis-ai-architecture.md` | Full system architecture |
| `docs/execution-plan.md` | Phased production roadmap |
| `docs/requirements-summary.md` | Functional + non-functional requirements |
| `docs/architecture-components.md` | Service definitions |
| `docs/cost-estimates.md` | Azure cost breakdown |
| `docs/diagrams/` | Architecture and screen-flow diagrams |

---

## PoC reference

A working end-to-end proof of concept (Spring Boot + React) lives at:
`https://github.com/sauloos/genesis-brands-poc`

It validates the specialist agent pipeline and the client journey flow.
The PoC is not the foundation for this codebase — it is a reference implementation.
