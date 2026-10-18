# Widget Modularisation — Plan

Agreed 2026-10-17, after the agent modularisation effort (`docs/agent-modularisation-plan.md`).
Goal: every widget is an independently built, self-contained module that an app adopts by
declaring a Gradle dependency, `flow-runtime.html` is pure chrome, and every customer-facing
page is a PageFlow.

## Audit (2026-10-17)

| Principle | Status before this plan |
|---|---|
| Server bean per widget, auto-collected | Met — 14 `WidgetDescriptor` beans (12 core, 2 brands) |
| Client module per widget at `static/widgets/<type>/` | 4 of 14 (login, dashboardAgents, dashboardAssets, dashboardCatalog) |
| New widget = bean + module, no host edit | Broken — `flow-runtime.html` dispatches through a hardcoded `widgetRegistry` and silently drops unknown types |
| Core never contains tenant code | Violated — `brandResults` UI and an engagement-claim registration screen live in core `flow-runtime.html` |
| Same module everywhere | Partial — the inline registration screen duplicates the login widget |
| Theme-token CSS only | No — hex in flow-runtime and in the dashboard widgets' CSS |
| Widget owns its backend | No — `PaymentController`, `ProductSelectionController`, `BrandResultsController` sit loose in the apps |
| Extend, don't copy | No mechanism (agents have `AgentViewExtension`) |
| Mount contract fits real widgets | `ctx` was only `{simulate, onOutcome}` |

Customer-facing pages that are not flows: `/your-brand/{id}` (`your-brand.html`) and the unused
`index.html` in both apps. `/discover` and `/questionnaire-run` are admin-only legacy pages.
genesis-os has no `/login` or `/register` flow.

## Target layout

```
os/widgets/<type>/        :os:widgets:<type>      core widgets
brands/widgets/<type>/    :brands:widgets:<type>  Genesis Brands widgets (brandResults, dashboardCatalog)
```

A widget module contains its whole contract:

```
src/main/java/.../<Type>Widget.java           WidgetDescriptor bean (component-scanned)
src/main/java/...                             its own controllers/services, if any
src/main/resources/static/widgets/<type>/     widget.js (+ widget.css)
```

Modules are auto-included by `settings.gradle`, exactly like `os/agents` and `brands/agents`.
**Adoption = Gradle dependency.**

## Mount contract

`export function mount(container, widget, ctx)` where `widget = { id, slotKey, orderInSlot,
widgetType, config }` and `ctx` is supplied by the host:

| ctx member | Meaning |
|---|---|
| `simulate` | admin preview session |
| `requireCompletion()` | this widget blocks the page's outcome buttons until `complete()` |
| `complete()` | release the block |
| `onOutcome(key?)` | the widget finished; the host advances the flow with `key` (default `next`) |
| `advance(key)` | move the flow directly (navigation widgets) |
| `outcomes` | the current page's outcomes `[{key, label}]` |
| `page` | `{ ended, widgetCount }` — whether this is the flow's end page, and how many widgets it has |
| `session` | `{ token, fetch(path, init) }` — calls `<flow-session API>/<token><path>` with the right headers/credentials |
| `saveContext(patch)` | merge a patch into the flow session context |

A widget that is still loading blocks the outcome buttons; once `mount` resolves the block is
released unless the widget called `requireCompletion()`.

`.outcome-btn` (with `.outcome-btn-back`) is a host-provided class. A widget that renders a
button which advances the flow uses it, and the host disables every `.outcome-btn` while any
widget blocks or the flow is advancing.

## Steps

1. **Host dispatch + ctx.** Any widget type without an inline entry mounts through `loader.js`;
   `ctx` gains the members above. login/dashboard* drop their registry entries; login owns its
   `redirectToNextParam` behaviour.
   *DONE.* `renderWidget()` falls back to `loader.js` for every type not in the shrinking
   `widgetRegistry`; the container keeps a `widget-<kebab-type>` class (widgets add their own
   classes with `classList.add`, never overwrite `className`). A failed module load logs a
   warning and releases the page. Mount calls are tied to a render generation, so a late widget
   never blocks or advances a page it no longer belongs to.
2. **Module skeleton.** `os/widgets/<type>` + `brands/widgets/<type>` auto-included; move the 4
   existing module widgets (bean + static files) into them. Duplicate-path test for
   `static/widgets/**` like `AgentViewPathsTest`.
   *DONE.* The contract (`WidgetDescriptor`, `WidgetConfigOption`, `WidgetOutcome`) moved to a
   plain-Java `os/widget-sdk` (os/runtime exposes it as `api`). Modules are named by
   widgetType: `os/widgets/{login,dashboardAgents,dashboardAssets}` and
   `brands/widgets/dashboardCatalog`; each depends only on the widget SDK plus
   `spring-context` (compileOnly). Both apps declare the widgets they adopt. `loader.js` and
   `agent-loader.js` stay in os/runtime. `AgentViewPathsTest` became `ModuleStaticPathsTest`,
   covering both `static/agent-views/**` and `static/widgets/**`.
3. **Extract the inline widgets**, simplest first: content, button, pageNav, question, redirect
   → logout → productOptions (+ `ProductSelectionController`) → payment (+ `PaymentController`)
   → questionnaire → brandResults (brands, + `BrandResultsController`). The registry is gone and
   `flow-runtime.html` is chrome.
   *3a DONE.* content, button, pageNav, question and redirect are modules under `os/widgets/`.
   The server no longer special-cases redirect (`PageRenderView.redirectUrl` is gone). The
   redirect widget forwards at once when it is alone on a page, otherwise after 1.5s. In an admin
   simulation it only shows its target, so the author can keep stepping through the flow.
   *3b DONE.* logout is a module. Its sign-out unlinks the client through `ctx.session`, and in
   an admin simulation the button does nothing.
   *3c DONE.* productOptions is a module that carries its own `ProductSelectionController`.
   `CartService` stays in the runtime, because payment reads the same cart. The module compiles
   against the runtime as `compileOnly`, like the consultant agent module.
   *3d DONE.* payment is a module that carries its own `PaymentController` (cart, checkout,
   status and mode endpoints). `PaymentService` and `CartService` stay in the runtime. A
   successful payment still advances the flow itself, now through `ctx.advance('next')`.
   *3e DONE.* questionnaire is a module. It blocks the page until the visitor submits, records
   its response id in the session context through `ctx.saveContext`, and finds its own elements
   through closures instead of document-wide ids. `/api/questionnaires/**` stays in the
   runtime, because the questionnaire admin pages use it too. The host's `putContext`,
   `widgetConfigBool` and `questionnaireStates` are gone.
   *3f DONE — step 3 complete.* brandResults is a Genesis Brands module
   (`brands/widgets/brandResults`) that carries its own `BrandResultsController` (the start
   handshake). It never touches the `Engagement` model: it calls a `BrandResultsEngagements`
   port, which brands/app implements (`EngagementBrandResults`). This is the pattern for any
   tenant widget backend that needs app data. In an admin simulation it explains that the
   pipeline needs a real visitor session and releases the page. The `widgetRegistry` is gone:
   `renderWidget()` mounts every type through `loader.js`. genesis-os does not adopt the module.
   *Found for step 4:* `FlowEngagementService` and `PageFlowService` hardcode the `question` and
   `questionnaire` types to harvest answers server-side. The content upload endpoints still live
   in `PageWidgetService` (runtime), though they belong to the content widget.
4. **Tenant code out of core.** The end-of-flow registration screen is replaced by the login
   widget plus a tenant hook for claiming an engagement; core stops calling `/api/engagements`.
5. **Flows only.** Retire `your-brand.html` into the `/live/dashboard` flow (old URL redirects),
   delete both `index.html` files and the legacy `/discover` + `/questionnaire-run` pages, add a
   login flow on genesis-os.
6. **Theme tokens + extensions.** Token-only widget CSS; a `WidgetExtension` mechanism mirroring
   `AgentViewExtension`.
7. **Record the principles** in CLAUDE.md.

Every step: build, run tests, smoke-test locally with Playwright, deploy both apps, check
production, commit.

After this effort: review the model the UI uses as a backend mirror.
