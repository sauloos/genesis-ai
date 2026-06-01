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

**What it contains:** The agency's codified methodology. How to read a client brief,
what signals matter, how to derive vision and mission, what makes a strong brand mark,
how to evaluate copy quality. Blog posts, video transcripts, workshop methodology
documents, and the rules that govern every stage of the brand-building process.

**How it serves Genesis AI:** This is *how to think*, not what to look up. Layer 1
is loaded as context at the start of every session — it is the agency's IP encoded
as reasoning rules. When Genesis AI decides whether a brief is complete, evaluates a
logo concept, or chooses between creative directions, it is applying Layer 1.

**Technical characteristics:**
- Structured, tagged reasoning modules (intake phase, brief evaluation, visual
  direction, copy tone, assembly criteria)
- Authored and versioned deliberately — updated when methodology evolves
- Not a loose corpus: more like a knowledge graph or rule system than a RAG store
- Always available to Genesis AI; retrieved by module type, not by semantic similarity

**Authored by:** The agency team. This encodes the craft.

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

Genesis AI is a long-horizon orchestration agent. It does not generate content
directly — it reasons, decides, dispatches, and evaluates.

### Responsibilities

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

### The "satisfied" signal

Genesis AI knows when it has enough information because Layer 1 defines explicit
completeness criteria for a brief — the same criteria a human strategist would apply
before going into concept development. This is what makes the conversational extension
purposeful rather than open-ended.

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
