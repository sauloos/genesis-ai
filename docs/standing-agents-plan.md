# Standing agents plan

Agents that keep working for a client after the engagement ends: on a schedule or on an
event, inside the client's brand context, with every output waiting for the client's
approval. They are what turns a one-off brand package into a subscription.

Agreed 2026-10-19:
- **Admins make an agent available; the client configures it.** The admin decides which
  agents a tenant/product offers and the limits (max frequency, allowed values). The client
  chooses the options: frequency, style, asset kinds, channels, and so on.
- **Meta (Facebook, Instagram) and LinkedIn publishing are in scope**, planned from the
  start rather than bolted on.
- **Draft first, publish on approval.** No agent publishes anything a human hasn't approved.
  Auto-publish may come later as an explicit per-subscription opt-in.

---

## The agents

| Agent | Placement | Trigger | Produces |
|---|---|---|---|
| **Content** | tenant (`brands/agents/content`) | schedule | Social posts (copy + image) per channel, for approval and scheduled publishing |
| **Brand guardian** | tenant (`brands/agents/brand-guardian`) | schedule, upload | Report scoring the client's live site/socials/uploads against their own brand book, with fixes |
| **Occasions** | tenant (`brands/agents/occasions`) | schedule (looks N weeks ahead) | Seasonal/launch assets (flyer, banner, promo post) proposed ahead of the date |
| **Market watch** | core (`os/agents/market-watch`) | schedule | Brief on what competitors changed and what it means for this client |
| **Client pulse** | core (`os/agents/client-pulse`) | schedule | Weekly digest: what happened, what's pending, next best action |
| **Lead-to-proposal** | core (`os/agents/lead-proposal`) | event (inbox/RFP feed) | Qualified lead + proposal draft from Layer 2 precedent |
| **Knowledge gardener** | core (extends the ingestion pipeline) | schedule | New sources ingested; contradictions with Layer 1 modules flagged |

Placement follows the CLAUDE.md test: a competitor watch, a weekly digest or a proposal
drafter is useful to any tenant (the client's own context supplies the lens); a content
calendar grounded in a brand book, a brand-compliance audit and brand-asset occasions are
Genesis Brands' product.

The existing OS utility agents (writer, summarizer, research-synthesis, support-faq) stop
being products in their own right and become tools these agents call.

---

## Core runtime (os/runtime + os/agent-sdk)

### SDK: an agent opts in

```java
// os/agent-sdk — an agent that can run unattended implements this alongside CoreAgent.
public interface StandingAgent {
    String agentId();
    Set<TriggerKind> supportedTriggers();          // SCHEDULE, EVENT, UPLOAD
    List<AgentCustomOption> subscriptionOptions(); // what the client configures
    Set<Cadence> allowedCadences();                // DAILY, WEEKLY, FORTNIGHTLY, MONTHLY
    StandingRunResult run(StandingRunContext ctx); // one unattended run
}
```

- `AgentCustomOption` gains `MULTI_SELECT`, `NUMBER`, `TEXT` (long) and `CHANNEL` (pick from
  the client's connected accounts), plus `helpText`. The same schema renders the client
  form, the admin defaults form and the Playground form; no agent ships its own settings UI
  unless it wants to (then it's an `AgentViewExtension`, as today).
- `StandingRunContext` carries the subscription's options, the client identity, the
  resolved brand context (server-side, per run, never from the request; same boundary as the
  customer-aware Consultant), the run's credit budget and the tools it may call.
- `StandingRunResult` is a list of **inbox items** (drafts or reports), never a direct side
  effect.

Auto-collected like `CoreAgent`: an agent module that implements `StandingAgent` becomes
schedulable with zero runtime wiring.

### Data model (core, `ddl-auto: update`)

- `StandingAgentOffer`: admin side, one per agentId (later per product, see Entitlements):
  `enabled`, default options JSON, locked options, allowed cadences, max runs/month.
  It extends `AgentCatalogConfig`'s per-surface flags with `availableAsStanding`.
- `StandingAgentSubscription`: client side: `clientUserId`, `agentId`, `active`,
  `cadence`, `dayOfWeek`/`timeOfDay`/`timezone`, options JSON, `nextRunAt`, `lastRunAt`,
  `pausedReason` (e.g. budget exhausted, channel disconnected).
- `StandingAgentRun`: one execution: status, started/finished, credits used, error, log.
- `InboxItem`: `kind` (DRAFT_POST, ASSET, REPORT, PROPOSAL), payload JSON, status
  (PENDING → APPROVED / REJECTED / EDITED → SCHEDULED → PUBLISHED / FAILED), the
  per-channel variants, the scheduled publish time and the published post ids/URLs.
  Approve/reject/regenerate-with-feedback reuse the semantics of `AgentRegenerationJob`
  (the regeneration approval gate), generalised from "one SLOT" to "any draft".

### Scheduler

`StandingAgentScheduler`, the `QuestionnairePublishScheduler` / `IngestionPipelineScheduler`
pattern: poll due subscriptions, start a run per subscription on a bounded executor, advance
`nextRunAt` from the cadence. Runs must be idempotent per `(subscriptionId, slot)`, so a
restart or a second replica never double-runs. Use a DB claim (update ... where status =
'DUE') instead of an in-memory lock, because the Container App can scale out.

### Guardrails

- **Credits.** A usage ledger records tokens and image generations per run against the
  client. This doesn't exist yet and is a prerequisite: the subscription pauses itself
  (`pausedReason = BUDGET`) when the month's allowance is used. It is also the meter for
  the Brain Credits billing model.
- **Approval by default**, audit of every publish, a client-side pause switch and an admin
  kill switch per agent.
- **Notifications.** "3 posts are waiting for approval": email plus a badge on
  `/live/dashboard`. Needs an outbound email service (none today; pick one, e.g. Azure
  Communication Services).

### Client UI (core widgets, `os/widgets/*`)

- `agentSubscriptions`: lists the standing agents offered to this client. Each card opens
  that agent's options form (rendered from `subscriptionOptions()`), the cadence picker,
  pause/resume and next run time.
- `agentInbox`: pending items with preview per channel (how the post looks on Instagram vs
  LinkedIn), Approve / Edit / Reject / Regenerate-with-feedback, then the publishing
  schedule.
- `channelConnections`: connect/disconnect Meta and LinkedIn accounts, token health.
- Later, `contentCalendar`: the month at a glance, drag to reschedule.

All four are placed on the `/live/dashboard` flow as data, like the existing dashboard
widgets, and call core endpoints backed by SPIs; none imports a tenant model.

### Admin UI

The agents admin page gets a "Standing" section per agent: available yes/no, default
options, which options are locked, allowed cadences, monthly run cap. A runs page shows
recent runs across clients, failures and credit use.

### Connectors

Every capability is offered as MCP + REST by default: subscriptions, runs and the inbox are
exposed through the Agent connector (a client's own model can approve drafts) and the
Platform connector (admin operations, collected from the admin APIs automatically).

---

## Publishing channels

### SPI

```java
// os/runtime: core owns OAuth connect, token storage/refresh, scheduling, retries, audit.
public interface PublishChannel {
    String channelId();                       // "facebook-page", "instagram", "linkedin-org", ...
    ChannelCapabilities capabilities();       // formats, max chars, image ratios, carousel size
    AuthorizationStart beginConnect(String clientUserId, String redirectUri);
    ConnectedAccount completeConnect(String clientUserId, Map<String, String> callback);
    PublishResult publish(ConnectedAccount account, ChannelPost post);
    Optional<PostMetrics> metrics(ConnectedAccount account, String postId); // later
}
```

Channel modules are core (`os/channels/meta`, `os/channels/linkedin`, auto-included like
agents): any tenant may publish. Agents produce channel-neutral drafts plus per-channel
variants shaped by `capabilities()`; the channel never knows which agent wrote the post.

Tokens are stored encrypted (Key Vault-backed key), never sent to the browser, and refreshed
by a scheduled job; an expiring or revoked token pauses the affected subscriptions and
notifies the client.

### Meta (Facebook Pages + Instagram professional accounts)

- One OAuth flow (Facebook Login for Business) yields the client's Pages; an Instagram
  professional account is reached through its linked Page.
- Publishing permissions: `pages_manage_posts`, `pages_read_engagement`,
  `instagram_basic`, `instagram_content_publish` (plus `business_management` where needed).
- **App Review and Business Verification are required** before non-test users can connect.
  This is the long pole: start the submission (screencast, privacy policy, data deletion
  callback) as soon as phase 3 starts.
- Instagram publishes from a **public image URL** (create container, then publish): the
  approved image needs a public, time-limited Blob URL (SAS), not an `/api/assets` URL
  behind auth.
- Rate limits: Instagram caps API-published posts per account per 24h; the scheduler
  respects it per account.

### LinkedIn

- Company pages: the Community Management API (`w_organization_social`,
  `r_organization_social`), which needs LinkedIn product approval for the developer app.
  Personal profiles: "Share on LinkedIn" (`w_member_social`), available without review.
- Access tokens last about 60 days; refresh tokens are granted only to approved partners.
  Plan for the client to re-authorise if refresh isn't granted, with a reminder before
  expiry.
- Images are uploaded first (Images API), then referenced in the post.

API names, scopes and review requirements must be re-checked against Meta's and LinkedIn's
developer docs when phase 3 starts; both platforms change them often.

---

## The content agent in detail

Client options (from `subscriptionOptions()`):

| Option | Type | Example |
|---|---|---|
| Channels | CHANNEL multi | Instagram, LinkedIn page |
| Posts per week | NUMBER (capped by admin) | 3 |
| Asset kinds | MULTI_SELECT | single image, carousel, story, text-only, LinkedIn article |
| Content pillars | MULTI_SELECT + TEXT | product, behind the scenes, education, community |
| Style | SELECT + TEXT | brand voice (default), more playful, more formal; notes |
| Image style | SELECT | brand photography, illustration, typographic, palette-only |
| Language | SELECT | en, pt, es |
| Avoid | TEXT | topics, words, competitors |

Each run:
1. Load brand context (copy, tone guide, palette, typography, logo) from the client's
   engagement, plus the last N approved/rejected posts and their feedback, so it learns what
   this client approves.
2. Plan the week: pillar and kind per slot, no repeats of recent topics.
3. Write per-channel copy (Copy agent's tone guide; LinkedIn longer, Instagram shorter with
   hashtags).
4. Generate images (`gpt-image-1`, palette and logo constraints; carousel = several frames).
5. Evaluate with the copy/visual evaluators (reused from the SLOT agents); loop or drop.
6. Emit one `InboxItem` per post with its variants and a proposed publish time.

---

## Entitlements

Which standing agents a client gets should follow what they bought. Products in the
Product/ProductOptions catalog grant agents with limits (e.g. "Social subscription: content
agent, 12 posts/month, 2 channels"). Until that lands, `StandingAgentOffer` applies to every
client of the tenant.

---

## Phases

| # | Phase | Delivers |
|---|---|---|
| 0 | Usage ledger — **done 2026-10-19** | Per-run token/image metering against a client; admin view. Prerequisite for budgets and billing. |
| 1 | Standing runtime | SDK `StandingAgent`, offer/subscription/run/inbox model, scheduler, admin section, `agentSubscriptions` + `agentInbox` widgets, connector exposure. Proven with a trivial core agent (client pulse). |
| 2 | Content agent, draft-only | Content agent producing drafts into the inbox; client approves and downloads. Real value before any platform review clears. |
| 3 | Channels | `PublishChannel` SPI, `channelConnections` widget, Meta + LinkedIn modules, token vault, scheduled publish. Submit Meta App Review / LinkedIn product access at the start of this phase. |
| 4 | Brand guardian | Site/social/upload audits against the brand book via the existing evaluators; reads recent posts through the connected channels. |
| 5 | Occasions + market watch | Calendar-driven asset proposals; competitor briefs using the ingestion crawler. |
| 6 | Lead-to-proposal, knowledge gardener | Event triggers (inbox/RFP), ingestion-pipeline extension. |
| 7 | Metrics loop | Pull post metrics; the content agent weights pillars/kinds by what performs. |

**Phase 0 as built** (os/runtime, package `ai.genesisbrands.usage`): `MeteredChatModel` wraps
the primary `ChatModel` (both profiles), so every Spring AI chat call, streamed or not, writes a
`UsageEvent` (model, tokens, images, cost in micro-dollars, client, agent, source, run). Cost
comes from `UsagePricing`: built-in list prices by longest model-id prefix, overridable under
`genesis.usage.prices.<prefix>.{input-per-mtok,output-per-mtok,per-image}`. Attribution is a
thread-bound `UsageScope`: `UsageScopeInterceptor` opens one per `/api/**` and `/mcp/**`
request (the client from a connector key or the session cookie, resolved only when a model is
called), `UsageTaskDecorator` carries it into `@Async` work, and a standing run will set it
with `UsageContext.callWith` (agent id + run id). The agent comes from the config key the
agent last read through `AgentProperties.get`. Image calls outside Spring AI's chat model
(Logo: gpt-image-1, Ideogram) record through `UsageService.recordImage`. Admin page:
`/dashboard/usage` (`UsageNavExtension`). `UsageService.spentThisMonthMicros(clientUserId)`
is the hook for phase 1 budgets. Embeddings are not metered yet (fractions of a cent).

Email notifications land with phase 1 or 2; entitlements with phase 2 at the latest, before
any client pays for the subscription.

## Open questions

- Pricing of the subscription tiers (posts/month, channels, agents included).
- Whether a client's team (more than one user) approves, which needs roles on client accounts.
- Which email provider sends notifications.
- Whether auto-publish is ever offered, and on which tier.
