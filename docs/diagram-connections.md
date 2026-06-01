# Diagram Connections Reference

All components and their connections for cross-checking against 06-deployment diagram.
Format: `Source → Target (label/reason)`

---

## Internet

- Internet → Azure Front Door

---

## Edge

- Azure Front Door → NGINX Ingress Controller `(dynamic: /api/* /ws/*)`
- Azure Front Door → Blob Storage `(static: /* assets, dashed)`

---

## namespace: ingress

- NGINX Ingress Controller → Spring Cloud Gateway
- Spring Cloud Gateway → Identity Service
- Spring Cloud Gateway → Brand Engine
- Spring Cloud Gateway → Conversation Service
- Spring Cloud Gateway → Order Service
- Spring Cloud Gateway → Delivery Service
- Spring Cloud Gateway → Admin Service
- Spring Cloud Gateway → Designer Marketplace Service `(Phase 3)`
- Spring Cloud Gateway → Strapi CMS
- Spring Cloud Gateway → Redis `(rate limit counters + token blacklist)`

---

## namespace: core

**Identity Service**
- Identity Service → PostgreSQL
- Identity Service → Stripe
- Identity Service → Redis `(JWT token blacklist)`

**Brand Engine**
- Brand Engine → Conversation Service `(initiates questionnaire session)`
- Brand Engine ↔ Cosmos DB `(read/write BrandDNA — bidirectional)`
- Brand Engine → Service Bus `(publishes brand lifecycle events)`
- Brand Engine → PostgreSQL `(Content Library — pgvector similarity queries + embedding storage)`
- Brand Engine → Azure OpenAI `(generates embeddings when content is approved into library)`

**Conversation Service**
- Conversation Service → Azure OpenAI `(signal extraction from answers)`
- Conversation Service → Cosmos DB `(writes extracted signals to BrandDNA)`
- Conversation Service → Redis `(conversation context / session state, TTL 30 min)`

**Order Service**
- Order Service → PostgreSQL
- Order Service → Print Partners `(Printful · MOO · Gelato)`
- Order Service → Merch Partners `(Printify)`

**Delivery Service**
- Delivery Service → Blob Storage `(writes assembled brand kit ZIP + PDF)`

**Notification Service**
- Notification Service → PostgreSQL `(delivery log)`
- Notification Service → SendGrid

**Admin Service**
- Admin Service → PostgreSQL

---

## namespace: generation

**Copy Generator**
- Service Bus → Copy Generator `(consumes brand.copy.requested)`
- Copy Generator → Brand Engine `(retrieves copy style anchors from Content Library before generation)`
- Copy Generator → Azure OpenAI `(generates all brand copy)`
- Copy Generator → Cosmos DB `(writes generated copy into BrandDNA)`
- Copy Generator → Service Bus `(publishes brand.step.completed COPY)`

**Visual Generator**
- Service Bus → Visual Generator `(consumes brand.visual.requested)`
- Visual Generator → Brand Engine `(retrieves visual direction examples from Content Library before generation)`
- Visual Generator → Azure OpenAI `(colour, typography, image prompts)`
- Visual Generator → Image Gen / Stability AI `(generates logo concepts + mood board images)`
- Visual Generator → Blob Storage `(uploads raw generated images)`
- Visual Generator → Cosmos DB `(writes Blob URLs + visual metadata into BrandDNA)`
- Visual Generator → Service Bus `(publishes brand.step.completed VISUAL)`

**Asset Composer**
- Service Bus → Asset Composer `(consumes brand.step.completed COPY + VISUAL)`
- Asset Composer → Brand Engine `(retrieves approved layout patterns from Content Library)`
- Asset Composer → Blob Storage `(writes final composed assets — logo variants, banners)`
- Asset Composer → Cosmos DB `(writes final asset URLs into BrandDNA)`
- Asset Composer → Service Bus `(publishes brand.step.completed ASSETS)`

---

## namespace: cms

**Strapi CMS**
- Strapi CMS → Blob Storage `(media uploads)`

---

## namespace: marketplace (Phase 3)

**Designer Marketplace Service**
- Designer Marketplace Service → PostgreSQL
- Designer Marketplace Service → Blob Storage `(portfolio + submission uploads)`
- Designer Marketplace Service → Stripe Connect `(escrow + designer payouts)`
- Designer Marketplace Service → Cosmos DB `(read-only — BrandDNA as brief)`

---

## Service Bus (event fan-out)

- Service Bus → Copy Generator
- Service Bus → Visual Generator
- Service Bus → Asset Composer
- Service Bus → Delivery Service
- Service Bus → Notification Service

---

## Azure Managed — Ops

- App Configuration → Spring Cloud Gateway `(runtime config, dashed)`
- App Configuration → Admin Service `(runtime config, dashed)`
- Key Vault → AKS Cluster `(CSI secret driver, dashed)`
- Container Registry → AKS Cluster `(image pull, dashed)`
- AKS Cluster → Azure Monitor `(logs & metrics, dashed)`

---

## Summary count

| Component | Outbound connections |
|---|---|
| Internet | 1 |
| Azure Front Door | 2 |
| NGINX | 1 |
| Spring Cloud Gateway | 10 |
| Identity Service | 3 |
| Brand Engine | 5 |
| Conversation Service | 3 |
| Order Service | 3 |
| Delivery Service | 1 |
| Notification Service | 2 |
| Admin Service | 1 |
| Copy Generator | 4 |
| Visual Generator | 5 |
| Asset Composer | 4 |
| Strapi CMS | 1 |
| Designer Marketplace | 4 |
| Service Bus (fan-out) | 5 |
| App Configuration | 2 |
| Key Vault | 1 |
| Container Registry | 1 |
| AKS Cluster | 1 |
