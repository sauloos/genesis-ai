---
name: project_genesis_ai_two_modes
description: Genesis AI operates in two modes — background orchestrator and conversational consultant
metadata: 
  node_type: memory
  type: project
  originSessionId: f97ee9fe-1885-48f5-b884-56341b0dc672
---

Genesis AI is not just a background pipeline — it also has a direct conversational interface.

**Mode 1 — Engagement:** Background orchestrator. Runs the structured brand generation pipeline asynchronously (questionnaire → directions → dispatch → evaluate → assemble → ingest Layer 2). This is the async, deliverables-producing path.

**Mode 2 — Consultant:** Conversational creative director interface. Same model and knowledge base, but synchronous and dialogue-driven. User can ask brand strategy questions, get opinions, explore scenarios, and Genesis AI can propose actions (trigger new engagement, update BrandDNA). Conversation history persisted per brand.

**Why:** The user wants to be able to consult Genesis AI as if talking to a creative director — not just kick off a pipeline. The two modes share the same intelligence so advice is always grounded in the same methodology and brand context.

**How to apply:** When designing the UI and API, account for both modes. The consultant interface needs: streaming chat endpoint, per-brand conversation history, BrandDNA context injection, and the ability to propose (but not auto-execute) engagement actions.
