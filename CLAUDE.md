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
principle, not a per-widget decision. It applies to every current and future widget type,
including any tenant-custom widget added later. **Every customer-facing page is a PageFlow**
made of widgets: there are no hand-written customer pages (`your-brand.html`, `index.html`,
`/discover` and `/questionnaire-run` were retired 2026-10-18). Flows are data, so a flow an app
needs on day one, such as `/login`, is seeded per environment (`deploy/seed-login-flow.py`).

A widget bundles everything it needs to run: UI, logic, its simulate-mode behaviour, its
config/outcome model and **its own backend**. It is loaded dynamically, never hardcoded in a page:

- **Server-side metadata.** A plain `@Component` bean implementing `WidgetDescriptor`
  (`os/widget-sdk`): `widgetType()`, `displayName()`, `configOptions()`, `outcomes()`.
  It is auto-collected, like `CoreAgent`; there is no registry and no hardcoded type list.
  A new config key needs both the client-side use and a `configOptions()` entry.
- **Client-side implementation.** A static module at `static/widgets/<widgetType>/widget.js`
  (+ optional `widget.css`), resolved purely by the `widgetType` string through
  `static/widgets/loader.js`'s `mountWidget(type, container, widget, ctx)`.
- **Its backend.** Controllers that exist for one widget live in that widget's module
  (`PaymentController` in payment, `ProductSelectionController` in productOptions,
  `ContentAssetController` in content). Shared services that admin pages also use stay in
  the runtime (`CartService`, `PaymentService`, `/api/questionnaires/**`).

**Mount contract.** `export function mount(container, widget, ctx)`, where `widget = { id,
slotKey, orderInSlot, widgetType, config }`. `ctx` is supplied by the host, and a host may supply
any subset:

| ctx member | Meaning |
|---|---|
| `simulate` | admin preview session: never take real actions (no payments, sign-outs, pipelines) |
| `requireCompletion()` / `complete()` | block the page's outcome buttons until the widget is done |
| `onOutcome(key?)` | the widget finished; the host advances the flow with `key` (default `next`) |
| `advance(key)` | move the flow directly (navigation widgets) |
| `outcomes`, `page` | the page's outcomes `[{key, label}]`; `{ ended, widgetCount }` |
| `session` | `{ token, fetch(path, init) }`, calls the flow-session API with the right headers |
| `saveContext(patch)` | merge a patch into the flow session context |

A widget adds its classes with `classList.add` and never overwrites the container's
`className`, because the host sets `widget-<kebab-type>` on it. It finds its own elements
through closures, never document-wide ids. A button that advances the flow uses the
host-provided `.outcome-btn` (`.outcome-btn-back`), which the host disables while any widget
blocks or the flow is advancing.

**The host is pure chrome.** `flow-runtime.html` mounts every type through `loader.js`; it has no
per-type code. Adding a widget, including a tenant's own, means one descriptor bean plus one
static module in a widget module. It never requires editing a host page or a core dispatch
table. A widget used from several places (in a flow, in `/dashboard/widget-preview`) uses the
*same* module every time, never a page-specific reimplementation. The admin preview,
`/dashboard/widget-preview?type=<type>&config=<json>`, mounts any widget in simulate mode.

**Theme tokens only.** Widget CSS styles itself only through `--theme-*` variables, so the
active theme decides the look. Tints use `color-mix()` over a token. `/widgets/tokens.css`
(os/runtime, loaded once by `loader.js`) is the only file allowed colour literals. It holds
zero-specificity defaults for the tokens a theme may leave out: `--theme-danger`, `-warning`,
`-success`, and `--theme-preview-bg`/`-check` for client artwork. `WidgetCssTokensTest` fails on
any hex, `rgb()`/`hsl()` or named colour in widget CSS.

**Extend, never copy.** A module that changes another module's widget registers a
`WidgetExtension` bean (`widgetType`, `module`, `order`) and ships the extension module.
`/widgets/extensions.json` lists them, and `loader.js` chains `mount(container, widget, ctx,
base)` over the base module, innermost first, loading the extension's same-named `.css`. This
mirrors `AgentViewExtension`. `ModuleStaticPathsTest` fails the build if a `static/widgets/**`
path exists twice on the classpath.

**Core widgets never touch tenant models.** When core needs something tenant-specific, it defines
a port or SPI in os/runtime and the tenant implements it:
- `FlowEngagementTrigger.nextUrl` says where a visitor goes after a CREATE_ENGAGEMENT end.
- `FlowAnswerSource` beans (one per answer-capturing widget type) let the runtime harvest answers
  without hardcoding types.
- `ApiKeyExemption` beans add public routes to `ApiKeyFilter`.
- `ClientWorkspaceProvider` backs the dashboard widgets.

A tenant widget whose backend needs app data does the same: it ships a port that the app
implements. For example, brandResults calls `BrandResultsEngagements`, which brands/app
implements as `EngagementBrandResults`. The widget never imports `Engagement`.

---

## Agent Pluggable Architecture

**Agents are pluggable components, exactly like widgets.** This is a standing architectural
principle, not a per-agent decision — it applies to every current and future agent, including
ones not yet designed (e.g. flyer, banner, signage, standup-banner, or any other asset-type
agent), whether built inside this application or shipped as its own module.

An agent bundles three things, and is added to the app with zero core wiring — `CoreAgent`
beans are already auto-collected via Spring component scan, same as `WidgetDescriptor`:

- **Its full definition** — default config in its own `agents/<id>/agent.yml` (loaded as the
  lowest-priority source under `genesis.agents.<id>.*`; an app's `application.yml` overrides only
  what it changes), system prompt at `agents/<id>/system.md` (by convention), and logic.
  Admins can override model, max tokens, max iterations, RAG and the system prompt per config
  key at runtime (`agent_settings_overrides`, agents admin page) — the top layer. Always read
  config via `AgentProperties.get(key)` / `systemPrompt(key)` per request, never cache a prompt
  in a field. Config keys are not agent ids: a key belongs to the agent with the longest
  matching id (`copy` owns `copy-evaluator`).
- **Its metadata** — `agentId`, `displayName`, `description`, `icon`.
- **Its own screens** — a Playground view and a Live Dashboard view, each a self-contained
  client-side module resolved by convention from `agentId` (`static/agent-views/<agentId>/
  playground.js` and `.../live.js`), exactly like a widget's `widgetType` resolves to
  `/widgets/<type>/widget.js` via `loader.js`. Resolution is convention-based — no registry.
  **Extend a view, never copy it to the same path.** A module that customises another module's
  view registers an `AgentViewExtension` bean (agentId, view, module); `agent-loader.js` chains
  its `mount(container, agent, ctx, base)` over the base view. `ModuleStaticPathsTest` fails the
  build if a `static/agent-views/**` path exists twice on the classpath.

**An agent's module placement follows what it is, not where it's wired in** — same rule as
widgets, and already reflected in the codebase today: **Consultant is a Genesis OS (core)
agent** (its own module, `os/agents/consultant`) because every
tenant gets a consultant — it's platform-level, sector-agnostic capability. **Copy, Visual
Identity, Logo, Playbook, and Brand Book are Genesis Brands (tenant) agents** (one module each,
`brands/agents/{copy,visual-identity,logo,playbook,brand-book}`) because they are
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
`availableForLiveView` is a **security boundary, not just a card filter**: every customer-facing
execution path (`/api/client/**`, engagement regenerate, Consultant customer tools, and future
connectors) must call `AgentCatalogService.requireAvailableForLiveView(agentId)` (404 when off).
All non-customer agent surfaces (`/api/agent-generation`, `/api/agent-chat`, `/api/consultant`,
`/api/playground`, `/api/agents/**` except `live-view`) require admin credentials via
`AdminAgentApiFilter` — the shared `X-Api-Key` ships in public JS and never counts as admin.

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
`ConsultantToolProvider` do for Consultant (see below). **Fixed 2026-10-06:**
`DashboardAgentsWidget`/`DashboardAssetsWidget` (both the `WidgetDescriptor` bean and the
`widget.js`) now live in `os/runtime` and call the generic `GET /api/client/workspace`
endpoint, backed by the core `ClientWorkspaceProvider` SPI
(`ClientWorkspaceConfiguration`/`ClientWorkspaceController`, conditionally registered exactly
like `ConsultantController`). `EngagementWorkspaceProvider` (genesis-brands) is the tenant
implementation translating `Engagement`/`DirectionOutput` into the generic `ClientWorkItem`
shape. The actual byte-serving mechanics (PDF render, logo zip, preview image,
approve/reject) stay in genesis-brands' `EngagementController` — those are genuinely
tenant-specific, not a platform mechanism; the SPI only supplies which URLs/labels/flags to
show, same division of labor as `ConsultantSubjectProvider`.

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
so the *mechanics* always live in os/runtime: the act of fetching context, the act of
deciding when/whether to attach tools to the Prompt, the generic shape of "a subject" and "a
tool." What a tenant supplies is the *content* behind those mechanics, through a core-defined
interface it implements — never by core reaching into a tenant model directly:
- **Fetching context is core; providing it is tenant.** `ConsultantSubjectProvider` (core
  interface, os/runtime) is what `ConsultantService` calls to fetch a subject's context —
  core owns retrieval, history, and streaming. `BrandConsultantSubjectProvider` (genesis-brands)
  is the implementation that actually knows about `Engagement`/`resultsJson`/`Brand` and
  produces the context DTO — that domain knowledge never belongs in os/runtime.
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
  os/runtime first, implement the tenant-specific content in genesis-brands second —
  never let a convenience shortcut put tenant model types (`Engagement`, `Brand`, etc.) inside
  os/runtime code.

Since 2026-10-17
every agent has its own Playground view module. `playground.html` is pure chrome that mounts
`agent-views/<id>/playground.js`. The 5 SLOT agents share `brands/app`
`agent-views/_shared/slotPlayground.js` and supply only their output renderers. Agent-view CSS
uses only the `--av-*` tokens from `_shared/tokens.css`, which each host maps to its theme.
Admin-editable config over `agent.yml` and view extensions (plan step 7) are done. This section is the
target state every agent, current or future, must be checked against.

---

## Standing Agents

**Standing agents** run unattended for a client on a schedule (see
`docs/standing-agents-plan.md`). An agent opts in by implementing `StandingAgent` (os/agent-sdk)
next to `CoreAgent`. `os/standing` collects every such bean, so there is no wiring.

- **Admins offer, clients configure.** The admin page `/dashboard/standing` sets whether an agent
  is offered, its cadences, runs per month, monthly spend cap and default or locked options.
  "Offered" means the offer is enabled AND `availableForLiveView`, and every agent starts off.
  Clients subscribe and tune the agent through the `agentSubscriptions` widget.
- **The inbox is the only output.** A run returns inbox items and must never publish, send or
  change anything itself. The client approves or rejects each item in `agentInbox`, and that
  feedback goes to the agent's next run.
- **Context is server-side.** The run's client context comes from core SPIs
  (`ClientWorkspaceProvider`, `StandingContextContributor`), never from a request.
- **Files go through the runtime.** An agent returns images and documents as
  `StandingRunResult.Attachment` bytes; `os/standing` stores them and serves them to the
  item's own client. Agents never touch storage.
- **Agent memory.** A run may return `StandingRunResult.memory`, a string of at most 200k
  characters. `os/standing` keeps it on the subscription and hands it to the next run as
  `StandingRunContext.memory()`. It is saved only after a successful run and is never shown to
  the client. Use it for what an agent must not repeat or must compare against (proposed
  occasions, page snapshots), never for client data that has a real home.
- **Reading websites.** Agents read public pages through the core `WebsiteReader` (`os/web`),
  which refuses private addresses and caps size. Never fetch URLs with a bare HTTP client.
- **Entitlements and email.** An offer may require products the client has paid for. Email on
  new items is Spring Mail, inert until `spring.mail.host` + `genesis.standing.notify.from`.
- **Metering and connectors.** Runs are metered per run id in the usage ledger. The client's own
  subscriptions and inbox are exposed as connectors (REST `/api/connect/standing`, MCP
  `standing_*` tools via `ClientToolContributor`).
- **Event triggers.** An `EVENT` agent declares `eventFields()` and `eventLabel()`; the client
  sends one through `agentSubscriptions` (`POST .../subscriptions/{agentId}/events`) or the
  connectors (`standing_send_event`), and the run reads `StandingRunContext.event()`. Lead to
  proposal (`os/agents/lead-proposal`) is event-only. Its precedent is the client's own past
  proposals (agent memory) plus the closest Layer 2 engagements.
- **Knowledge search.** An agent that needs Layer 1 or Layer 2 asks optionally for the SDK port
  `KnowledgeSearch`; os/runtime implements it (`RetrievalKnowledgeSearch`).
- **Ingestion listeners.** `IngestionEventListener` (os/runtime) beans are called after each
  ingestion event finishes. The Knowledge gardener (`os/agents/knowledge-gardener`, admin page
  `/dashboard/gardener`, off by default) uses it to flag contradictions and gaps between new
  Layer 1 sources and the reasoning modules. Only admins edit the modules.
- **Reading what a client published.** An agent that reviews a client's posts asks for the core
  port `SocialPostReader` (agent SDK) optionally; `os/publishing` implements it over the
  client's connected accounts through `PublishChannel.recentPosts`. A REPORT that scores
  something adds `payload.scorecard` (shape in `StandingRunResult`), which `agentInbox` renders.
  The Brand guardian (`brands/agents/brand-guardian`) uses both. Its website reads go through
  the SSRF-guarded `WebsiteReader`.
- **Learning what performs.** `PostMetricsScheduler` (os/publishing) pulls each published post's
  figures through `PublishChannel.metrics` for 30 days. An agent that wants to know how its own
  posts did asks for the optional SDK port `PostPerformance`, which `os/standing` implements.
  The Content agent weights its topics and formats by it (`ContentPerformance`).
- **Publishing to social accounts is client-initiated, never automatic.**
  - A platform is a channel module (`os/channels/<id>`, auto-included). It implements
    `PublishChannel` (os/channel-sdk).
  - `os/publishing` owns OAuth connect, encrypted tokens (`TokenVault`), scheduled publish jobs
    with retries, and signed media links. Channels never store anything themselves.
  - Clients connect accounts in the `channelConnections` widget. From `agentInbox` they publish
    an APPROVED post now or schedule it (`StandingPublishing`, also as connectors).
  - Inert until `genesis.publishing.token-key`, `genesis.publishing.public-base-url` and a
    channel's app credentials (`genesis.channels.<id>.*`) are set.
  - See `docs/standing-agents-plan.md`, "Phase 3 as built".

## Module Layout

Gradle aggregators (see `docs/agent-modularisation-plan.md`): `os/` = Genesis OS —
`os/agent-sdk` (`CoreAgent`, `AgentCustomOption`), `os/runtime` (platform library, formerly
brain-engine-core), `os/agents/<id>` (one module per OS agent, auto-included by settings.gradle),
`os/app` (the genesis-os bootJar). Each agent module is self-contained: `<Id>CoreAgent` +
`agents/<id>/agent.yml` + `agents/<id>/system.md` + `static/agent-views/<id>/`. **Adoption =
Gradle dependency**: an app has exactly the agent modules it declares. The runtime never depends
on an agent. `ModuleOrigin.isCore()` = "loaded from an os library module". All OS agents
(summarizer, support-faq, compliance-qa, writer, rfp-response, research-synthesis, consultant)
are modules. Consultant's module carries its service, admin + customer controllers and a core
live view (`consultantChat.js` base); a tenant extends it through the subject/tool/resolver
SPIs, `genesis.consultant.*` persona config, `genesis.agents.consultant.*` model/prompt config,
and an `AgentViewExtension` (`consultant/ext/brands-live.js`) that passes its own copy to the
base view. Controllers that must only be registered by a conditional `@Bean` are marked
`@RegisteredByConfiguration`, which every app excludes from component scan.
`brands/` = Genesis Brands — `brands/app` (the genesis-brands bootJar: Engagement/Brand,
orchestration, Consultant extension, `agent-views/_shared/regenerate.js`) and `brands/agents/<id>`
(auto-included like `os/agents`) for the 5 SLOT agents. Inter-agent deps are explicit Gradle
deps (playbook → copy, visual-identity, logo; brand-book → those + playbook). Brand-book owns its
HTML templates, template entity/controller and Playwright.

Widgets follow the same layout (see `docs/widget-modularisation-plan.md`): `os/widget-sdk`
(`WidgetDescriptor`, `WidgetConfigOption`, `WidgetOutcome`), one module per widget named by its
widgetType under `os/widgets/<type>` (core) or `brands/widgets/<type>` (tenant), auto-included by
settings.gradle, each carrying its descriptor bean, its own controllers and
`static/widgets/<type>/`. A widget module depends only on the widget SDK (plus the runtime as
`compileOnly` when it needs runtime services). Adoption = Gradle dependency: genesis-os does not
adopt the Genesis Brands widgets (brandResults, dashboardCatalog).

---

## External Connectors (MCP + REST)

**Every capability the platform exposes externally is offered as both MCP and REST, by
default.** This is a standing architectural principle (agreed 2026-10-17), not a per-agent or
per-feature decision — it applies to OS core agents, Genesis Brands agents, and any future
tenant custom agent alike. Three connector families exist, separated by audience:

| Connector | Audience | Exposes |
|---|---|---|
| **Agent connectors** (Consultant connector, Logo connector, Copy connector, …) | Customers' own models/apps | Each agent enabled for that customer |
| **Brain connector** | Customers' own models/apps | Knowledge retrieval over Layer 1 + Layer 2 — no agent reasoning |
| **Platform connector** | Tenant admins / operators | Admin operations — everything the admin dashboard can do (page flows, widgets, agent config, knowledge/ingestion, products, themes, questionnaires, …) |

Naming is fixed: "Consultant connector" and "Brain connector" — never "ask the brain" /
"search the brain".

- **Agent connectors are generated, never hand-written.** One core-owned gateway in
  os/runtime derives each agent's MCP tools and REST endpoints from `AgentCatalogService`
  (the same auto-collected `CoreAgent` beans Playground and `/live/dashboard` use). Adding a
  pluggable agent automatically adds its connectors, with zero gateway code. Exposure is gated
  by an admin per-surface flag (alongside `availableForPlayground`/`availableForLiveView`),
  exactly like the other surfaces.
- **Connector behaviour follows the deployment, not the connector.** Consultant exists in core
  and is extended in Genesis Brands via `ConsultantSubjectProvider`/`ConsultantToolProvider`;
  connecting to genesis-os yields the pure OS Consultant, connecting to genesis-brands yields
  the extended, customer-aware one. No connector-side branching — it falls out of the SPI split.
- **Identity and brand context are resolved server-side.** Agent/Brain connectors authenticate
  the caller (API key / OAuth) to a tenant + client identity; brand context is always loaded
  from the database per request — never accepted from the caller (same security boundary as
  the customer-aware Consultant above).
- **The Platform connector is admin-privileged** — its own admin-scoped credentials and audit
  trail, never the customer keys used by agent/Brain connectors. Its operations are collected
  by convention from the existing admin APIs (core mechanics in os/runtime; tenant-only
  admin operations contributed through a core-defined SPI), so a new admin page becomes a new
  Platform connector capability without editing a central list.
- **Long-running agents need job semantics.** SLOT agents (multi-minute, approval-gated)
  are exposed as start / poll status / approve-or-reject, not a single blocking call.

**Built (2026-10-17) in `os/connectors`**, adopted by both apps:
- **Credentials:** `ConnectorKey` bearer keys, stored hashed.
  - `gbc_…` is CLIENT, bound to one client user. Clients self-issue them at
    `/api/client/connector-keys`; admins can also issue them at `/api/admin/connector-keys`.
  - `gbp_…` is PLATFORM and admin-issued only.
  - `ConnectorAuthFilter` guards `/api/connect/**` and `/mcp/**`. The public `X-Api-Key` never
    counts.
- **Endpoints:**
  - REST: `/api/connect/agents`, `/api/connect/brain/search` and `/api/connect/platform/operations`.
  - MCP (stateless JSON-RPC over POST): `/mcp/agents`, `/mcp/agents/{agentId}`, `/mcp/brain` and
    `/mcp/platform`.
- **Agent modes:**
  - JOB through the `ConnectorJobProvider` SPI.
  - CHAT through the `ConnectorChatProvider` SPI, or through generic chat.
  - GENERATE for generic single-shot agents.
  - An agent that needs an engagement and has no provider is not connectable.
  - The gate is `requireAvailableForConnectors`: the per-agent `availableForConnectors` flag
    (default off) AND live-view.
- **Brain layers:** set by `genesis.connectors.brain.layers`, default `layer1,layer2`.
  **Pending:** Layer 2 holds other clients' data and is not yet anonymised on ingest. It must
  be anonymised before external customers use the Brain connector.
- **Platform operations:** auto-collected from the handler mappings and filtered by
  `PlatformOperationFilter` beans (the tenant SPI). They run via an admin loopback call, and
  every call is audited in `platform_audit_log`.

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
