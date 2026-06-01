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
The agency's codified methodology. How to read a brief, what signals matter, how to
evaluate creative output. Structured YAML modules, version-controlled in this repo.
Updated deliberately when methodology evolves.

```
knowledge/
  layer1/
    intake/          brief completeness criteria, probing question frameworks
    directions/      brand archetype frameworks, direction brief templates
    evaluation/      copy, logo, and visual quality rubrics
    assembly/        playbook and brand book structure
```

### Layer 2 — Project Memory
Every completed client engagement — briefs, reasoning traces, deliverables, client
selections. Continuously ingested. Queried by semantic similarity + metadata filters.

```
knowledge/
  layer2/            ingested project records (via KnowledgeService)
  assets/            logos, brand books, campaign imagery (local → Azure Blob)
```

---

## Genesis AI — Orchestrator Agent

**Model:** Claude Opus 4 with extended thinking

**Responsibilities:**
1. Conduct client journey (structured questionnaire + conversational extension)
2. Decide when brief is complete (Layer 1 completeness criteria)
3. Derive three creative directions from brief + Layer 1 + Layer 2 precedent
4. Dispatch to specialist agents with direction briefs
5. Evaluate specialist output against Layer 1 rubrics — accept or loop
6. Instruct assembly of playbook and brand book
7. Trigger Layer 2 ingestion on completion

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
| Layer 1 store | YAML files in this repo | Same (deployed) |
| Layer 2 vector store | PostgreSQL + pgvector | Azure Database for PostgreSQL |
| Layer 2 assets | `knowledge/assets/` local | Azure Blob Storage |
| Embeddings | OpenAI text-embedding-3-small | OpenAI / Azure OpenAI |
| API layer | TBD | TBD |
| Frontend | TBD | TBD |

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
