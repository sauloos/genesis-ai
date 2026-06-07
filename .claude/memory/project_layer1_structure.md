---
name: project_layer1_structure
description: Layer 1 of the knowledge base has two sub-layers — raw sources and distilled modules
metadata: 
  node_type: memory
  type: project
  originSessionId: f97ee9fe-1885-48f5-b884-56341b0dc672
---

Layer 1 is split into two sub-layers:

- **Layer 1a — Knowledge Sources:** Raw corpus of blog posts, podcast transcripts, book highlights, course notes, workshop materials. These form the philosophical foundation — the *why* behind the agency's methodology. Not queried at runtime.
- **Layer 1b — Reasoning Modules:** Structured YAML rules distilled from Layer 1a sources by the agency team. These are loaded as context at runtime and govern how Genesis AI reasons at every stage.

**Why:** The sources come from many different external inputs (industry thinkers, frameworks, courses) and need to be synthesised into actionable rules before Genesis AI can apply them. The two-sub-layer design keeps runtime context clean and structured while allowing the methodology to evolve as new sources are added.

**How to apply:** When discussing or building Layer 1 tooling, always distinguish between source ingestion/processing (1a → 1b authoring workflow) and runtime module loading (1b → Genesis AI context).

Directory structure:
```
knowledge/layer1/sources/    # raw corpus
knowledge/layer1/modules/    # distilled reasoning modules
```
