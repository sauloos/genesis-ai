# Genesis AI — Architecture

## Overview

Genesis AI is the intelligent core of the Genesis Brands platform. It acts as a
creative director: it conducts the client journey, orchestrates specialist agents,
evaluates their output against the agency's methodology, and assembles the final
deliverables. The platform always produces three brand directions per engagement.

---

## The Two-Layer Knowledge Base

Genesis AI's reasoning is grounded in two distinct layers of knowledge that serve
fundamentally different purposes.

### Layer 1 — Reasoning Substrate (Foundation)

Layer 1 has two sub-layers that serve different purposes:

#### Layer 1a — Knowledge Sources (Raw Corpus)

**What it contains:** The raw materials that form the philosophy behind the agency's
methodology. Blog posts, podcast transcripts, book highlights, course notes, workshop
materials, recorded frameworks from practitioners the agency respects. This is the
intellectual foundation — the *why* behind every creative and strategic decision.

**Examples of source types:**
- Brand strategy and positioning frameworks (books, courses)
- Design philosophy writing (blog posts, essays)
- Podcast transcripts from practitioners in branding, positioning, copywriting
- Internal workshop recordings and methodology documents
- Annotated case studies

**How it's used:** These sources are processed and synthesised into Layer 1b reasoning
modules by the agency team. They are not queried at runtime — they are the upstream
input that keeps Layer 1b grounded in deep expertise rather than generic AI defaults.

**Grows with:** New sources added as the agency's philosophy evolves. Each addition
may trigger a revision to the relevant Layer 1b module.

#### Layer 1b — Reasoning Modules (Distilled Methodology)

**What it contains:** The agency's codified methodology, distilled from Layer 1a
sources into structured, actionable reasoning rules. How to read a client brief,
what signals matter, how to derive vision and mission, what makes a strong brand mark,
how to evaluate copy quality — the rules that govern every stage of the process.

**How it serves Genesis AI:** This is *how to think*, not what to look up. Layer 1b
modules are loaded as context at the start of every session — they are the agency's IP
encoded as reasoning rules. When Genesis AI decides whether a brief is complete,
evaluates a logo concept, or chooses between creative directions, it is applying Layer 1b.

**Technical characteristics:**
- Structured, tagged reasoning modules (intake phase, brief evaluation, visual
  direction, copy tone, assembly criteria)
- Authored and versioned deliberately — updated when Layer 1a sources evolve or new
  sources are added
- Not a loose corpus: more like a knowledge graph or rule system than a RAG store
- Always available to Genesis AI; retrieved by module type, not by semantic similarity

**Authored by:** The agency team. This encodes the craft, grounded in Layer 1a sources.

### Layer 2 — Project Memory (Experience)

**What it contains:** Every completed client engagement — workshops, brand playbooks,
brand books, logos, assets (business cards, website imagery, banners). Crucially, also
the *outcomes*: which direction the client chose, feedback received, how assets
performed.

**How it serves Genesis AI:** This is *what good looks like in practice*. When
working on a new brief, Genesis AI retrieves similar past projects to calibrate its
decisions — similar industry, similar brand archetype, similar aesthetic territory.
The outcomes data is the learning signal: over time, Genesis AI gets better at
predicting what will resonate.

**Technical characteristics:**
- Vector store + structured metadata index (industry, archetype, style adjectives,
  outcome, date)
- Retrieved on demand: "find three past projects in premium food with a purposeful
  archetype"
- Continuously ingested — every completed project feeds Layer 2
- Metadata schema is fixed at project creation; embeddings are regenerated as the
  model improves

**Grows with:** Every project delivered.

---

## Genesis AI — The Creative Director Agent

Genesis AI is the central intelligence of the platform. It operates in two modes that
share the same underlying model, knowledge base, and brand context:

---

### Mode 1 — Engagement (Background Orchestrator)

Genesis AI runs the full brand generation pipeline autonomously in the background.
It does not generate content directly — it reasons, decides, dispatches, and evaluates.

**Responsibilities:**

1. **Conduct the client journey** — Run the structured questionnaire (mirroring the
   agency's 4-hour workshop framework). If it needs more information to satisfy Layer 1
   completeness criteria, it continues conversationally. It decides when the brief
   is complete.

2. **Define the three creative directions** — Before dispatching to specialist agents,
   Genesis AI derives three distinct strategic positions for the brand from the brief
   and Layer 1 methodology. These are not arbitrary style levels; each direction is
   anchored to a specific brand archetype or strategic angle.

3. **Dispatch to specialist agents** — With a direction brief per variant, Genesis AI
   calls the specialist agents (copy, visual identity, logo, playbook assembly) and
   provides each with the relevant direction brief plus retrieved Layer 2 examples.

4. **Evaluate as creative director** — Genesis AI receives the output from each
   specialist, evaluates it against Layer 1 quality rubrics, and either accepts it
   or sends it back with directed feedback. This loop runs until the output meets
   the standard or a retry limit is reached.

5. **Assemble the deliverables** — Once all specialist outputs are accepted for a
   direction, Genesis AI instructs the Assembly Agent to compile the brand playbook
   and brand book.

**The "satisfied" signal:** Genesis AI knows when it has enough information because
Layer 1 defines explicit completeness criteria for a brief — the same criteria a human
strategist would apply before going into concept development.

---

### Mode 2 — Consultant (Conversational Interface)

Genesis AI is also directly accessible as a conversational creative director. This is
not a separate agent — it is the same Genesis AI with the same knowledge and context,
but in a synchronous, dialogue-driven mode.

**What it is:** A persistent chat interface where the user can consult Genesis AI at
any point in the brand lifecycle — before, during, or after an engagement. Genesis AI
responds in the voice of a senior creative director: opinionated, strategic, grounded
in the agency's methodology.

**Context available in every conversation:**
- The user's current BrandDNA (if a brand exists)
- Layer 1b reasoning modules (the full methodology)
- Retrieved Layer 2 precedent (similar past projects, on demand)
- History of previous consultant conversations for this brand

**Example interactions:**
- *"Is our tagline still on brand if we're moving upmarket?"*
- *"We're expanding into a European market — what should change and what should stay?"*
- *"Our competitor just rebranded to something very similar to our Evolved direction. What do you think?"*
- *"Walk me through why you chose the Disruptive direction for our brand."*
- *"I want to test a new positioning hypothesis — what questions should I be asking?"*

**Actions Genesis AI can offer from within a consultant conversation:**
- Suggest updating the BrandDNA to reflect a strategic shift
- Trigger a new engagement (re-run the pipeline with an evolved brief)
- Flag inconsistencies between the user's stated direction and their existing brand assets
- Pull in a specific Layer 2 precedent to illustrate a point

**Technical characteristics:**
- Streaming responses (SSE) for real-time feel
- Conversation history persisted per brand (not per session) — Genesis AI remembers
  previous consultant exchanges for this brand
- The same Layer 1 + Layer 2 retrieval pipeline as Engagement mode
- Can read but not directly write BrandDNA — any changes proposed are confirmed by the user

---

## The Three Brand Directions

Every engagement always produces three brand directions. These are not style variants
(e.g. "conservative / bolder / wild" as arbitrary dials). Each direction is a coherent
strategic and creative position derived from the brand signals:

| Direction | Character |
|---|---|
| 1 — Anchored | Expected territory for this brand. Safe, credible, immediately recognisable to the target audience. |
| 2 — Evolved | A step beyond the expected. Distinctive within the category, slightly challenging but still accessible. |
| 3 — Disruptive | Challenges category conventions. High risk, high reward. Only recommended when the brand has the conviction to live it. |

All three are internally coherent. None is a throwaway option.

---

## Specialist Agents

Genesis AI dispatches to these agents. Each receives a direction brief (derived
from the client brief + a specific creative direction) plus relevant Layer 2 examples.

| Agent | Produces |
|---|---|
| Copy Agent | Tagline, mission statement, brand story, elevator pitch, tone guide |
| Visual Identity Agent | Colour palette, typography, mood direction |
| Logo Agent | Logo mark concept (SVG or image generation depending on provider) |
| Playbook Assembly Agent | Structured brand playbook document |
| Brand Book Assembly Agent | Formatted brand book PDF |

Each agent runs within a Genesis AI evaluation loop — output is accepted or revised
before proceeding to assembly.

---

## Data Flow

```
Client journey (questionnaire + conversation)
        │
        ▼
Brief completeness check  ←── Layer 1 (completeness criteria)
        │
        ▼
Derive 3 creative directions  ←── Layer 1 (methodology) + Layer 2 (precedent)
        │
        ├─── Direction 1 brief ──▶ Specialist agents ──▶ Evaluate ──▶ Assembled output
        ├─── Direction 2 brief ──▶ Specialist agents ──▶ Evaluate ──▶ Assembled output
        └─── Direction 3 brief ──▶ Specialist agents ──▶ Evaluate ──▶ Assembled output
                                                                           │
                                                                           ▼
                                                                Client selects direction
                                                                           │
                                                                           ▼
                                                              Ingest into Layer 2
                                                         (brief + reasoning + assets + selection)
```

---

## Project Ingestion into Layer 2

When a project is completed and the client has made their selection, the following
is ingested into Layer 2:

- Brand signals (the completed brief)
- Genesis AI's reasoning trace (which directions it derived and why)
- All generated variants — including rejected ones
- Client selection and any feedback
- Final delivered assets

The reasoning trace is the high-value piece. It is what allows Genesis AI to learn
from experience rather than just accumulating examples.

---

## Relationship to the Current PoC

The current PoC validates the specialist agents and the linear generation pipeline.
The migration path to this architecture is:

1. Current PoC → working specialist agents (copy, visual, logo, assembly)
2. Add knowledge layer infrastructure (vector store + Layer 1 module structure)
3. Begin authoring Layer 1 reasoning modules
4. Promote `BrandService` orchestration into a Genesis AI agentic loop
5. Introduce multi-direction generation (three briefs, three parallel pipelines)
6. Wire in Layer 2 ingestion at project completion

The specialists themselves change minimally. The intelligence moves into Genesis AI
and the knowledge layers.

---

## Technology Targets

| Component | Dev | Production |
|---|---|---|
| Layer 1 store | Structured YAML/JSON modules | Azure Blob + versioned |
| Layer 2 vector store | Chroma (local) | Azure AI Search |
| Layer 2 metadata | PostgreSQL | Cosmos DB |
| Genesis AI agent | Claude (extended thinking / agentic) | Claude Opus 4.x |
| Specialist agents | Groq / Claude | Groq / Claude / Azure OpenAI |
| Image generation | SVG via LLM | DALL-E 3 / Flux |
