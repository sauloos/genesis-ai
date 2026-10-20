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
agent, 12 posts/month, 2 channels").

Built in phase 2: an offer may list required products (`StandingAgentOffer.requiredProducts`).
A client is entitled when they have a SUCCEEDED payment whose cart held one of them; an offer
with none is open to every client. Per-product limits (posts/month, channels) and renewals or
cancellations of recurring products are not tracked yet: a client who paid once stays entitled.

---

## Phases

| # | Phase | Delivers |
|---|---|---|
| 0 | Usage ledger — **done 2026-10-19** | Per-run token/image metering against a client; admin view. Prerequisite for budgets and billing. |
| 1 | Standing runtime — **done 2026-10-19** | SDK `StandingAgent`, offer/subscription/run/inbox model, scheduler, admin section, `agentSubscriptions` + `agentInbox` widgets, connector exposure. Proven with a trivial core agent (client pulse). |
| 2 | Content agent, draft-only — **done 2026-10-19** | Content agent producing drafts into the inbox; client approves and downloads. Real value before any platform review clears. |
| 3 | Channels — **built 2026-10-19, inert until configured** | `PublishChannel` SPI, `channelConnections` widget, Meta + LinkedIn modules, token vault, scheduled publish. Submit Meta App Review / LinkedIn product access at the start of this phase. |
| 4 | Brand guardian — **done 2026-10-19** | Site/social audits against the brand book; reads recent posts through the connected channels. Uploads not wired yet. |
| 5 | Occasions + market watch — **done 2026-10-20** | Calendar-driven post, banner and flyer proposals; competitor briefs from pages read with the shared website reader (not the ingestion crawler). |
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

**Phase 1 as built.**
- **SDK** (`os/agent-sdk`, `platform.standing`): `StandingAgent` (`subscriptionOptions`,
  `allowedCadences`, `defaultCadence`, `run`), `StandingRunContext` (options, client context,
  recent decisions, previous run, `Model.complete`), `StandingRunResult.Item` (REPORT /
  DRAFT_POST / ASSET / PROPOSAL) and `StandingContextContributor`. `AgentCustomOption` gained
  `helpText` and the TEXT / NUMBER types.
- **Runtime** (`os/standing`, adopted by both apps):
  - Entities `StandingAgentOffer`, `StandingSubscription`, `StandingRun`, `InboxItem`.
  - `StandingScheduler` polls each minute for due subscriptions. `StandingRunner` runs them on
    its own 2-thread pool, inside `UsageContext.callWith(agent, run)`.
  - A run is skipped, never failed, when the client is over `maxRunsPerMonth` or the client's
    month-to-date spend (all usage, not only this agent) is over `monthlyBudgetMicros`.
  - A run is unique per (subscription, slot), so a restart cannot double-fire.
  - Client context comes from core SPIs: `ClientWorkspaceProvider`, inbox history and any
    `StandingContextContributor`. It is never taken from the request.
- **Offers:** "Offered" means `StandingAgentOffer.enabled` AND `availableForLiveView`. There is no
  row by default, so every agent starts not offered. Admins can lock options; a locked value
  always wins over the client's.
- **APIs:**
  - Client, `/api/client/standing`: offers, subscribe (PUT), pause, resume, unsubscribe and
    run-now.
    - Run-now returns 409 while a run is in progress, and 429 within 10 minutes of the
      previous manual run.
    - Also runs, inbox, approve / reject with feedback, and `inbox/count`.
  - Admin, `/api/admin/standing`: offers, subscriptions with pause / resume, runs with cost.
  - Connectors: REST under `/api/connect/standing` and MCP tools `standing_*` on `/mcp/agents`,
    through the new `ClientToolContributor` SPI in `os/connectors`.
- **UI:**
  - `/dashboard/standing` is contributed by the module through `AdminNavExtension`.
    `BasicAuthFilter` now protects every extension's `/dashboard/*` path and its `.html`.
  - Widgets `agentSubscriptions` and `agentInbox`, both with simulate data.
  - `dashboardAgents` now lists only agents with a live view, so a standing-only agent is not
    shown as a dead card.
- **First agent:** `os/agents/client-pulse`, a weekly / fortnightly / monthly account report
  (focus, length, language, notes). It uses the client's feedback on earlier reports.
  - A run costs about $0.006 on Sonnet 5.
  - Not built yet: email notifications.

**Phase 2 as built.**
- **Content agent** (`brands/agents/content`, tenant: it is grounded in the brand book; adopted
  by genesis-brands only). Options: channels (Instagram, Facebook, LinkedIn), posts per run
  (1–7), formats (single image, carousel, story, text only), topics plus free-text notes,
  writing style, image style (brand photography, illustration, typographic, palette only, no
  images), language, avoid. Weekly, fortnightly or monthly.
  - One Sonnet 5 call plans and writes the batch as JSON; each post carries a `day` within the
    period (now to the next run, in the client's zone) that becomes `proposedFor`.
  - Images: `gpt-image-1` at medium quality (1024×1024, 1024×1536 for stories; carousels up to
    3 frames), metered as `gpt-image-1-medium` ($0.042). A failed image drops that frame, not
    the post. A two-post run with three images cost about $0.15.
  - Not done (deliberately): reusing the copy / visual evaluators, editing a draft in place and
    regenerate-with-feedback. A rejection note feeds the next run instead.
- **SDK.** `StandingRunResult.Item` gained `attachments` (`Attachment(name, contentType, data)`)
  and a fixed DRAFT_POST payload shape (variants per channel, format, pillar, proposedFor).
  `StandingRunContext` gained `nextRunAt` and `zone`.
- **Runtime.** `StandingAttachments` stores run files in blob storage under
  `standing/<client>/<item>/` (10 per item, 15 MB each, images / PDF / text only). The client
  gets `GET /api/client/standing/inbox/{id}/attachments/{n}` and `.../{id}/download` (a zip of
  `post.md`, one `<channel>.txt` per variant, and the files). Both need the client session;
  `StandingFileRoutes` exempts them from the `X-Api-Key` header so `<img>` and links work.
- **Email.** `StandingNotifier` sends a plain-text email when a run leaves items, through Spring
  Mail (any SMTP, e.g. Azure Communication Services). Off until `spring.mail.host` and
  `genesis.standing.notify.from` are set; `genesis.standing.notify.inbox-url` adds a link.
  Clients opt out per subscription ("Email me when results arrive").
- **Entitlements.** See above; `/dashboard/standing` has an "Only for clients who bought"
  product picker and shows whether email is configured.
- **Widgets.** `agentInbox` renders a DRAFT_POST as a post: date / format / topic chips, image
  strip, one tab per channel with a Copy button, and Download post. `agentSubscriptions` has
  the email checkbox.

**Phase 3 as built.** It differs from the SPI sketch above in one way: a channel module is a
*provider* (one OAuth app) that can yield accounts on several networks. Meta gives Facebook
Pages and the Instagram accounts linked to them, so one connect flow adds both.
- **SDK** (`os/channel-sdk`, `platform.channel`):
  - `PublishChannel`: `providerId`, `displayName`, `networks()` (each with its id, label, max
    characters, max images and whether it requires an image), `configured()`,
    `authorizationUrl`, `completeConnect(code, redirectUri)` returning `ChannelGrant`s, and
    `publish(account, post)`.
  - `ChannelException` carries `retryable()` and `reauthorize()`.
  - `ChannelHttp` is a small JSON-over-HTTP helper.
  - The runtime never depends on a channel module.
- **Channels** (`os/channels/<id>`, auto-included like agents, adopted by both apps):
  - `meta`:
    - Facebook Login for Business with long-lived Page tokens, Graph `v26.0`.
    - A Facebook post is text, one photo, or several unpublished photos attached to a feed post.
    - An Instagram post is one image or a carousel published through a media container, and
      must have an image.
    - Config: `genesis.channels.meta.{app-id,app-secret,graph-version,scopes}`.
  - `linkedin`:
    - OpenID Connect plus `w_member_social` for the member's own profile.
    - Organisation pages are added when `w_organization_social` is in
      `genesis.channels.linkedin.scopes`, which needs Community Management approval.
    - Images go through the Images API, then a Posts API post. The commentary is escaped for
      LinkedIn's "little text" format, keeping hashtags.
    - Versioned header `202610`.
    - Config: `genesis.channels.linkedin.{client-id,client-secret,api-version,scopes}`.
- **Runtime** (`os/publishing`):
  - **Accounts.** `ChannelAccount` is unique per client, provider and external id. Its token is
    encrypted with AES-GCM by `TokenVault`, using the base64 32-byte key in
    `genesis.publishing.token-key`. Tokens are never sent to the browser.
  - **Expiry.** An account is marked EXPIRED when its token's expiry passes, or when the
    platform answers that it needs re-authorising. No refresh flow exists: LinkedIn gives
    refresh tokens only to partners, and Meta Page tokens do not expire. The client reconnects.
  - **Jobs.** A `PublishJob` holds the text and images of one post for one account at a time,
    up to 366 days ahead. Jobs are keyed by a `sourceRef`; for an inbox item that is
    `standing-inbox:<itemId>`.
  - **Scheduling.** `PublishScheduler` runs every minute. It claims due jobs with a conditional
    update, so two replicas cannot both send the same job.
  - **Retries.** A retryable failure retries after 5, then 20 minutes, with at most 3
    attempts.
  - **Interrupted posts.** A job left RUNNING for more than 15 minutes is marked FAILED, not
    retried, because the post may already be live.
  - **Images.** They are normalised to JPEG and served at `/api/channels/media/{token}.jpg`
    through a signed link that expires after 2 hours, for Meta to fetch.
  - **OAuth state.** It is signed, binds the client, the provider and a same-origin return
    path, and expires after 15 minutes. The callback also requires the signed-in client to
    match.
- **APIs:**
  - Client, `/api/client/channels`: list providers and accounts, connect, disconnect (which
    also cancels the account's waiting posts), and list or cancel posts.
  - Public, `/api/channels`: the OAuth callback and media links. Both are exempt from the API
    key through `ApiKeyExemption`.
  - Admin, `/api/admin/channels`: setup status (which keys are missing) and the latest posts.
- **Standing:**
  - `StandingPublishing` publishes an APPROVED post. Each channel version of the post goes to
    accounts on the matching network, now or at a chosen time, with the item's images.
  - The item stays APPROVED; what went out lives in the jobs.
  - Client endpoints: GET, POST and DELETE on `/api/client/standing/inbox/{id}/publish`.
  - Connectors: the same under `/api/connect/standing/...`, plus the MCP tools
    `standing_publish_options` and `standing_publish_item`.
  - Publishing is never automatic: the client always chooses to publish.
- **UI:**
  - New core widget `channelConnections` ("Social accounts"): connect, reconnect and
    disconnect.
  - `agentInbox` shows a Publish section on an approved post. It has account checkboxes per
    channel version, a time that defaults to the proposed day at 10:00, Publish now and
    Schedule, and the post's jobs with View post and Cancel. The section hides itself when
    publishing is off.
  - `/dashboard/standing` has a "Social publishing" block.
- **To turn it on:**
  - Set `GENESIS_PUBLISHING_TOKEN_KEY` (`openssl rand -base64 32`) and
    `GENESIS_PUBLISHING_PUBLIC_BASE_URL`.
  - Set one platform's app credentials (`GENESIS_CHANNELS_META_APP_ID` / `_APP_SECRET`,
    `GENESIS_CHANNELS_LINKEDIN_CLIENT_ID` / `_CLIENT_SECRET`).
  - Register `<base>/api/channels/callback/{meta|linkedin}` as the redirect URI.
  - Add `channelConnections` to the client dashboard flow.
  - Until Meta App Review and Business Verification clear, only the Meta app's own testers
    can connect.
- **Not built:** post metrics (phase 7), Instagram's 24-hour publish cap per account, video,
  stories as a story format (they post as a feed image), and reminders before a LinkedIn
  token expires.

**Phase 4 as built.**
- **Agent** (`brands/agents/brand-guardian`, tenant: it judges against the brand book; adopted
  by genesis-brands only). Weekly, fortnightly or monthly (default monthly).
  - Options: up to 5 website pages, whether to check social posts, posts per account (3–20),
    focus areas (voice and tone, messaging, colours, typography, consistency), notes, language.
  - One Sonnet 5 call (`agents/brand-guardian/system.md`) returns a score, per-area scores and
    at most 8 findings, each with evidence, where it was seen and a fix. Posts since the last
    run are marked new. A rejection note feeds the next run.
  - No model call when the client has no brand book yet, or when nothing could be read; the
    inbox gets a short report saying what to do instead.
- **Website reading.** `WebsiteReader` (jsoup) fetches each page and up to 3 linked stylesheets,
  and extracts the title, description, headings, visible text, the most used colours and the
  font families. It only reads public sites:
  - https/http on ports 80 and 443, no user info in the address.
  - Every resolved address must be public (no loopback, private, link-local, CGNAT, ULA or
    mapped addresses).
  - Redirects are followed by hand, at most 3, each one checked again.
  - 2 MB per response, 5 s to connect, 10 s per request.
  - Residual risk: DNS rebinding between the check and the connect.
- **Reading posts.** New core port `SocialPostReader` (agent SDK), implemented by
  `PublishingService` over the client's connected accounts, and asked for optionally, so an app
  without publishing still runs the agent on its website. `PublishChannel.recentPosts` defaults
  to none:
  - Meta reads Facebook Page posts and Instagram media (captions; video thumbnails) with the
    `pages_read_engagement` and `instagram_basic` permissions already in the default scopes.
  - LinkedIn reads nothing: reading an organisation's posts needs Community Management access.
  - An account whose token was refused is marked EXPIRED and listed as unreadable in the report.
- **Scorecard.** A REPORT may carry `payload.scorecard` (shape in `StandingRunResult`).
  `agentInbox` renders it as the score with its band, per-area meters, findings with severity,
  what works, quick fixes and the list of what was checked. The plain body stays the fallback.
- **Not built:** the UPLOAD trigger (judging a file the client sends), looking at images
  (only captions, colours and fonts are judged), and reading LinkedIn posts.

**Phase 5 as built.**
- **Agent memory (SDK).** A run may return `StandingRunResult.memory` (a string, at most
  200,000 characters). `os/standing` stores it on the subscription (`agent_memory`, saved with a
  targeted update so a client editing settings mid-run is not overwritten) and hands it to the
  next run as `StandingRunContext.memory()`. It is only saved after a successful run, and it is
  never shown to the client. Occasions and Market watch use it; the older agents return none.
- **Shared website reader.** `WebsiteReader` moved from the Brand guardian into a new core
  library module, `os/web` (package `ai.genesisbrands.platform.web`), with the same public-site
  rules. Brand guardian and Market watch both use it.
- **Occasions** (`brands/agents/occasions`, tenant: it draws with the Content agent's images
  and works from the brand book; adopted by genesis-brands only). Weekly, fortnightly or
  monthly (default fortnightly).
  - Options: region (US, UK, Ireland, Canada, Australia, Brazil, Portugal, Spain, France,
    Germany, Global), weeks ahead (2–12), kinds of occasion, the client's own dates (`YYYY-MM-DD name`
    once, `MM-DD name` every year), occasions per run (1–4), days of lead time (0–21), what to
    make (social post, story, web banner, print flyer), channels, image style, language,
    avoid.
  - Dates that move (Easter, Thanksgiving, Black Friday, Mother's Day per region, bank
    holidays, Carnaval, Advent and so on) are computed in `KnownDates` and given to the model;
    it may only choose other occasions when it is sure of the date, and anything outside the
    window is dropped.
  - Per occasion: a DRAFT_POST (pillar "Occasion", proposed for the date minus the lead time,
    `payload.occasion` = name, date, why) with a square image, or a portrait one for a
    story-only client; and an ASSET item holding the story, the 1536×1024 banner and the
    1024×1536 flyer, with the headline drawn into them. They are a separate item because
    publishing a post sends all its images.
  - Memory remembers what was proposed, so an occasion is never proposed twice; entries are
    forgotten 60 days after their date.
  - Cost: one Sonnet 5 call (about $0.03–0.05) plus about $0.04 per gpt-image-1 medium image,
    so roughly $0.20–0.35 per occasion with all four assets.
- **Market watch** (`os/agents/market-watch`, core: any tenant's client has competitors;
  adopted by both apps). Weekly, fortnightly or monthly (default weekly).
  - Options: up to 6 pages, one per line with an optional name before the address; what to
    watch for (offers and pricing, products and services, messaging, content and news); a
    question; language.
  - Each run reads every page and keeps a snapshot (title, description, headings, text, hash)
    in memory. The first read is a baseline; later reads send the model only the added and
    removed sentences and headings of pages that changed. An unreadable page keeps its old
    snapshot.
  - No model call when no pages are set or nothing changed; the inbox gets a short report.
    The prompt treats page text as untrusted data.
  - The REPORT carries `payload.brief` (headline, competitors with status, changes and what
    each means, implications, up to three suggested moves, what to watch next, and the pages read).
  - Cost: one Sonnet 5 call, about $0.03 for three pages, only when something changed.
- **Inbox.** `agentInbox` shows the occasion as a chip on a post, previews an ASSET pack
  (each file at its own aspect ratio with a download), and renders a market brief.
- **To turn it on:** on `/dashboard/agents` set the agent available for Live view, then offer
  it on `/dashboard/standing`. Both start off.
- **Not built:** reading pages that need a browser (sites that block plain fetches answer
  403 and are listed as unreadable), watching competitors' social accounts, sitemaps or
  whole-site crawls, and occasions from a public holiday API.

## Open questions

- Pricing of the subscription tiers (posts/month, channels, agents included).
- Whether a client's team (more than one user) approves, which needs roles on client accounts.
- Which SMTP provider sends notifications on prod (Azure Communication Services is the obvious one).
- Whether auto-publish is ever offered, and on which tier.
