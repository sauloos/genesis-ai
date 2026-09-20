"""
Core ingestion pipeline.
Every source (YouTube, PDF, web, file) calls process() with extracted text.
Handles chunking, deduplication, embedding, and storage into Qdrant.
"""

import hashlib
import json
import os
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

from dotenv import load_dotenv
import anthropic as _anthropic_module
from openai import OpenAI
from qdrant_client import QdrantClient
from qdrant_client.models import Distance, PointStruct, VectorParams
from tqdm import tqdm

load_dotenv(Path(__file__).parent.parent / ".env")

QDRANT_URL = os.getenv("QDRANT_URL", "http://localhost:6333")
QDRANT_API_KEY = os.getenv("QDRANT_API_KEY", "")
COLLECTION_NAME = os.getenv("COLLECTION_NAME", "genesis-knowledge")
EMBEDDING_MODEL = os.getenv("EMBEDDING_MODEL", "text-embedding-3-small")
EMBEDDING_DIM = 1536
CHUNK_SIZE = int(os.getenv("CHUNK_SIZE", "800"))
CHUNK_OVERLAP = int(os.getenv("CHUNK_OVERLAP", "100"))
_KNOWLEDGE_DIR = Path(__file__).parent.parent / "knowledge"

_openai: Optional[OpenAI] = None
_qdrant: Optional[QdrantClient] = None
_anthropic: Optional[_anthropic_module.Anthropic] = None


def _chunks_dir(layer: str) -> Path:
    return _KNOWLEDGE_DIR / layer / "chunks"


def _get_openai() -> OpenAI:
    global _openai
    if _openai is None:
        _openai = OpenAI(api_key=os.getenv("OPENAI_API_KEY"))
    return _openai


def _get_anthropic() -> _anthropic_module.Anthropic:
    global _anthropic
    if _anthropic is None:
        _anthropic = _anthropic_module.Anthropic(api_key=os.getenv("ANTHROPIC_API_KEY"))
    return _anthropic


def _get_qdrant() -> QdrantClient:
    global _qdrant
    if _qdrant is None:
        from urllib.parse import urlparse
        parsed = urlparse(QDRANT_URL)
        is_https = parsed.scheme == "https"
        # qdrant_client always defaults to port 6333 even for https URLs.
        # Azure Container Apps only exposes 443, so we must pass host/port/https
        # explicitly when the URL has no explicit port.
        if parsed.port:
            port = parsed.port
        else:
            port = 443 if is_https else 6333
        kwargs = {
            "host": parsed.hostname,
            "port": port,
            "https": is_https,
            "check_compatibility": False,
        }
        if QDRANT_API_KEY:
            kwargs["api_key"] = QDRANT_API_KEY
        _qdrant = QdrantClient(**kwargs)
        _ensure_collection(_qdrant)
    return _qdrant


def _upsert_with_retry(points, max_attempts: int = 3, batch_size: int = 50) -> None:
    """POST points directly via REST in batches — avoids httpx connection-pool staleness."""
    import json as _json
    import time
    import requests

    url = QDRANT_URL.rstrip("/") + f"/collections/{COLLECTION_NAME}/points"
    headers = {"Content-Type": "application/json"}
    if QDRANT_API_KEY:
        headers["api-key"] = QDRANT_API_KEY

    for i in range(0, len(points), batch_size):
        batch = points[i : i + batch_size]
        payload = {
            "points": [
                {"id": p.id, "vector": p.vector, "payload": p.payload}
                for p in batch
            ]
        }
        for attempt in range(1, max_attempts + 1):
            try:
                resp = requests.put(url, headers=headers, data=_json.dumps(payload), timeout=120)
                resp.raise_for_status()
                break
            except Exception as e:
                if attempt == max_attempts:
                    raise
                wait = 5 * attempt
                print(f"  Qdrant upsert failed (attempt {attempt}/{max_attempts}): {e} — retrying in {wait}s...")
                time.sleep(wait)


def _ensure_collection(client: QdrantClient) -> None:
    existing = [c.name for c in client.get_collections().collections]
    if COLLECTION_NAME not in existing:
        client.create_collection(
            collection_name=COLLECTION_NAME,
            vectors_config=VectorParams(size=EMBEDDING_DIM, distance=Distance.COSINE),
        )
        print(f"Created Qdrant collection: {COLLECTION_NAME}")


_IMAGE_VISION_PROMPT = """\
You are a brand intelligence analyst. Examine this visual asset and produce two outputs.

PART 1 — Prose description (for knowledge retrieval):
Describe what you see in detail useful for an AI knowledge base. Cover: what type of visual this is,
visual style and aesthetic, colours (name them with approximate hex values), typography if present
(style, weight, spacing), composition and layout, any symbols or graphic devices, mood and brand
personality signals, and industry/audience positioning signals. Be specific. Plain prose, no headers.

PART 2 — Structured classification. Output a JSON block in exactly this format (no other text after):
```json
{
  "visual_type": "...",
  "logo_subtype": "...",
  "font_style": "...",
  "bg_tone": "...",
  "colour_temperature": "...",
  "industry_tier": "...",
  "brand_relevant": true
}
```
visual_type — what this image fundamentally is. Pick one:
  logo, photograph, illustration, chart_or_infographic, pattern_or_texture,
  mockup_or_screenshot, mood_board, icon_or_symbol, typography_specimen,
  colour_palette, product_shot, diagram, other

logo_subtype — only populate if visual_type is "logo", otherwise null. Pick one:
  icon_only, wordmark, combination_mark, emblem, monogram

font_style — dominant typeface style if text is present, else null. Pick one:
  geometric_sans, humanist_sans, grotesque_sans, transitional_serif, old_style_serif,
  slab_serif, script, display, monospace

bg_tone — pick one: light, dark, transparent, gradient, complex
colour_temperature — pick one: warm, cool, neutral, high_contrast
industry_tier — pick one: budget, mid_market, premium, luxury, unclear

brand_relevant — true if this image carries brand intelligence worth storing
  (logos, colour palettes, typography specimens, mood boards, brand photography,
  illustrations used as brand assets). false for generic stock photos, decorative
  clip art, UI screenshots with no brand signal, data charts, or filler imagery.\
"""

_MIN_IMAGE_BYTES = 2_000   # skip tiny icons / decorative blobs below this size
_MAX_IMAGE_BYTES = 5_000_000  # skip suspiciously large embedded objects


def extract_images_from_pdf(pdf_path: str) -> list[tuple[bytes, str]]:
    """
    Extract embedded images from a PDF.
    Returns list of (image_bytes, mime_type) tuples.
    Only includes images large enough to be meaningful brand assets.
    """
    import pymupdf
    results = []
    doc = pymupdf.open(pdf_path)
    seen_xrefs = set()
    for page in doc:
        for img in page.get_images(full=True):
            xref = img[0]
            if xref in seen_xrefs:
                continue
            seen_xrefs.add(xref)
            try:
                pix = pymupdf.Pixmap(doc, xref)
                if pix.n > 4:
                    pix = pymupdf.Pixmap(pymupdf.csRGB, pix)
                img_bytes = pix.tobytes("png")
                if _MIN_IMAGE_BYTES <= len(img_bytes) <= _MAX_IMAGE_BYTES:
                    results.append((img_bytes, "image/png"))
            except Exception:
                pass
    doc.close()
    return results


_IMAGE_MIME_TYPES = {
    ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
    ".png": "image/png", ".gif": "image/gif",
    ".webp": "image/webp", ".avif": "image/avif",
}
_MAX_WEB_IMAGES = 12  # cap per page — Vision calls are expensive


def extract_images_from_url(page_url: str) -> list[tuple[bytes, str]]:
    """
    Fetch a web page and extract meaningful images from it.
    Downloads each candidate, applies size filters, deduplicates by URL.
    Returns list of (image_bytes, mime_type) tuples.
    """
    import requests
    from bs4 import BeautifulSoup
    from urllib.parse import urljoin, urlparse

    try:
        resp = requests.get(page_url, timeout=15,
                            headers={"User-Agent": "Mozilla/5.0 (compatible; GenesisAI/1.0)"})
        resp.raise_for_status()
    except Exception:
        return []

    soup = BeautifulSoup(resp.text, "lxml")
    # Collect candidate src values: <img src>, <source srcset>, og:image meta
    candidates: list[str] = []
    for tag in soup.find_all("img", src=True):
        candidates.append(tag["src"])
    for tag in soup.find_all("source", srcset=True):
        # srcset may be "url 2x, url2 1x" — take first entry
        first = tag["srcset"].split(",")[0].strip().split()[0]
        candidates.append(first)
    for tag in soup.find_all("meta", property="og:image"):
        if tag.get("content"):
            candidates.insert(0, tag["content"])  # OG image first — usually logo/hero

    seen_urls: set[str] = set()
    results: list[tuple[bytes, str]] = []

    for src in candidates:
        if len(results) >= _MAX_WEB_IMAGES:
            break
        try:
            abs_url = urljoin(page_url, src)
            # Normalise: strip query strings for dedup
            dedup_key = abs_url.split("?")[0]
            if dedup_key in seen_urls:
                continue
            seen_urls.add(dedup_key)

            # Infer MIME from URL extension; skip obvious non-images
            ext = "." + dedup_key.rsplit(".", 1)[-1].lower() if "." in dedup_key else ""
            if ext in (".svg", ".ico"):
                continue  # vector icons — skip, not suitable for Vision
            mime = _IMAGE_MIME_TYPES.get(ext, "image/jpeg")

            img_resp = requests.get(abs_url, timeout=10,
                                    headers={"User-Agent": "Mozilla/5.0 (compatible; GenesisAI/1.0)"})
            if img_resp.status_code != 200:
                continue
            img_bytes = img_resp.content
            # Re-check MIME from Content-Type if extension was ambiguous
            ct = img_resp.headers.get("content-type", "")
            if ct.startswith("image/"):
                mime = ct.split(";")[0].strip()
            if not mime.startswith("image/"):
                continue
            if _MIN_IMAGE_BYTES <= len(img_bytes) <= _MAX_IMAGE_BYTES:
                results.append((img_bytes, mime))
        except Exception:
            continue

    return results


def _extract_dominant_colours(image_bytes: bytes, n: int = 5) -> list[str]:
    """Extract N dominant hex colours from image bytes using Pillow quantization."""
    try:
        from PIL import Image
        import io
        img = Image.open(io.BytesIO(image_bytes)).convert("RGB")
        img = img.resize((150, 150), Image.LANCZOS)
        quantized = img.quantize(colors=n, method=Image.Quantize.FASTOCTREE)
        palette = quantized.getpalette()[:n * 3]
        return [
            "#{:02X}{:02X}{:02X}".format(palette[i], palette[i+1], palette[i+2])
            for i in range(0, len(palette), 3)
        ]
    except Exception:
        return []


def describe_image_with_claude(
    image_bytes: bytes,
    mime_type: str = "image/png",
    user_context: Optional[str] = None,
) -> dict:
    """
    Use Claude Vision to classify and describe a visual asset.

    Returns a dict with:
      description      — prose text (embedded as the searchable chunk)
      visual_type      — what the image fundamentally is
      logo_subtype     — populated only when visual_type == "logo"
      font_style, bg_tone, colour_temperature, industry_tier
      dominant_colours — top hex values extracted from pixels
      brand_relevant   — bool; False means skip storing this chunk
      user_context     — echoed back if provided

    Returns None if the image is not brand-relevant and no user_context override.
    """
    import base64
    import re

    prompt = _IMAGE_VISION_PROMPT
    if user_context:
        prompt = (
            f"Context from the person who shared this image: \"{user_context}\"\n"
            f"Take this context into account — it may identify the asset's role "
            f"(e.g. 'this is our logo', 'colour palette reference').\n\n"
        ) + prompt

    client = _get_anthropic()
    response = client.messages.create(
        model="claude-haiku-4-5-20251001",
        max_tokens=1200,
        messages=[{
            "role": "user",
            "content": [
                {
                    "type": "image",
                    "source": {
                        "type": "base64",
                        "media_type": mime_type,
                        "data": base64.standard_b64encode(image_bytes).decode("utf-8"),
                    },
                },
                {"type": "text", "text": prompt},
            ],
        }],
    )
    text_block = next(b for b in response.content if b.type == "text")
    raw = text_block.text.strip()

    json_match = re.search(r"```json\s*(\{.*?\})\s*```", raw, re.DOTALL)
    if json_match:
        prose = raw[:json_match.start()].strip()
        try:
            meta = json.loads(json_match.group(1))
        except json.JSONDecodeError:
            meta = {}
    else:
        prose = raw
        meta = {}

    # user_context overrides brand_relevant — if the user said what it is, it's relevant
    if user_context:
        meta["brand_relevant"] = True

    meta["dominant_colours"] = _extract_dominant_colours(image_bytes)
    meta["description"] = prose
    if user_context:
        meta["user_context"] = user_context
    return meta


_ANONYMISE_SYSTEM = """\
You are a privacy specialist preparing brand strategy documents for use in an AI knowledge base.
Your task is to de-identify a document so it contains zero personally identifiable information (PII)
and zero company/brand-identifiable information, while preserving every strategic insight intact.

Rules:
1. Replace ALL personal names (founders, executives, consultants, clients) with their role:
   "the founder", "the CEO", "the creative director", "the head of marketing", etc.
   If multiple people share a role, number them: "the co-founder (1)", "the co-founder (2)".
2. Replace ALL company/brand names with a plain industry descriptor in lowercase:
   e.g. "DI9ITAL" → "a digital innovation agency", "Learn About Property" → "a property education brand".
   Derive the descriptor from context clues in the document. Be specific enough to be useful
   (e.g. "a B2B SaaS platform" not just "a company") but never name the actual entity.
3. Replace email addresses with [EMAIL], phone numbers with [PHONE], physical addresses with [ADDRESS].
4. Replace specific identifying URLs or social handles with a generic reference:
   e.g. "instagram.com/brandname" → "their Instagram", or just omit if it adds nothing.
5. Keep ALL strategic content: brand values, positioning, tone of voice, colour palettes,
   typography, messaging pillars, target audiences, competitive context, recommendations.
6. Keep ALL framework and methodology language intact — this is what makes the document valuable.
7. The output must read as natural, coherent prose — not obviously redacted.
   Use smooth replacements that don't interrupt the reader's flow.
8. Return ONLY the de-identified document text. No preamble, no explanation, no commentary.\
"""

_ANONYMISE_CHUNK_SIZE = 12_000  # characters — split large docs into overlapping sections


def _anonymise_document(text: str) -> str:
    """
    Anonymise an entire document using Claude before chunking.
    Runs on the full text for consistent entity replacement throughout.
    Splits very large documents into sections to stay within context limits.
    """
    client = _get_anthropic()

    # For documents that fit comfortably in one call, process in one shot
    if len(text) <= _ANONYMISE_CHUNK_SIZE:
        sections = [text]
    else:
        # Split on paragraph boundaries to avoid cutting mid-sentence
        paragraphs = text.split("\n\n")
        sections, current = [], ""
        for para in paragraphs:
            if len(current) + len(para) + 2 <= _ANONYMISE_CHUNK_SIZE:
                current += ("\n\n" if current else "") + para
            else:
                if current:
                    sections.append(current)
                current = para
        if current:
            sections.append(current)

    anonymised_parts = []
    for i, section in enumerate(sections, 1):
        if len(sections) > 1:
            print(f"  Anonymising section {i}/{len(sections)}...")
        response = client.messages.create(
            model="claude-haiku-4-5-20251001",
            max_tokens=16000,
            system=_ANONYMISE_SYSTEM,
            messages=[{"role": "user", "content": section}],
        )
        text_block = next(b for b in response.content if b.type == "text")
        anonymised_parts.append(text_block.text.strip())

    return "\n\n".join(anonymised_parts)


_NORMALISE_PROMPT = """\
Rewrite the following text so it reads as neutral brand strategy knowledge — remove or replace \
any references to specific agencies, companies, consultants, or clients by name. \
Replace "the agency" / "our agency" with "we". Replace named individuals with their role \
(e.g. "the founder", "the strategist"). Replace named client companies with a generic descriptor \
(e.g. "a healthcare brand", "a tech startup", "a B2B services firm") based on context. \
Keep all strategic insight, frameworks, and principles intact. Return only the rewritten text.\
"""


def _normalise_chunk(text: str) -> str:
    """Rewrite a chunk via GPT to strip entity references. Runs once at ingestion."""
    response = _get_openai().chat.completions.create(
        model="gpt-4o-mini",
        messages=[
            {"role": "system", "content": _NORMALISE_PROMPT},
            {"role": "user", "content": text},
        ],
        temperature=0,
    )
    return response.choices[0].message.content.strip()


def _chunk_text(text: str) -> list[str]:
    """Split text into overlapping chunks, respecting paragraph boundaries where possible."""
    words = text.split()
    chunks = []
    start = 0
    while start < len(words):
        end = min(start + CHUNK_SIZE, len(words))
        chunk = " ".join(words[start:end])
        chunks.append(chunk)
        if end == len(words):
            break
        start += CHUNK_SIZE - CHUNK_OVERLAP
    return [c for c in chunks if len(c.strip()) > 50]


def _embed(texts: list[str]) -> list[list[float]]:
    response = _get_openai().embeddings.create(model=EMBEDDING_MODEL, input=texts)
    return [item.embedding for item in response.data]


def _source_id(source_url: str) -> str:
    return hashlib.sha256(source_url.encode()).hexdigest()[:16]


def _is_already_ingested(source_url: str, layer: str = "layer1") -> bool:
    sid = _source_id(source_url)
    return (_chunks_dir(layer) / f"{sid}_meta.json").exists()


def _save_chunks(chunks_data: list[dict]) -> None:
    if not chunks_data:
        return
    layer = chunks_data[0].get("layer", "layer1")
    d = _chunks_dir(layer)
    d.mkdir(parents=True, exist_ok=True)
    sid = chunks_data[0]["source_id"]
    with open(d / f"{sid}_chunks.json", "w") as f:
        json.dump(chunks_data, f, indent=2)
    with open(d / f"{sid}_meta.json", "w") as f:
        json.dump({"source_url": chunks_data[0]["source_url"], "ingested_at": datetime.now(timezone.utc).isoformat(), "chunk_count": len(chunks_data)}, f, indent=2)


def process(
    text: str,
    source_url: str,
    source_type: str,
    title: str = "",
    author: str = "",
    date: str = "",
    layer: str = "layer1",
    force: bool = False,
    normalise: bool = True,
    anonymise: bool = False,
    content_category: str = "",
    image_descriptions: Optional[list[str]] = None,
) -> int:
    """
    Process extracted text through the full pipeline.
    Returns the number of chunks ingested (0 if skipped as duplicate).

    content_category — semantic type of the source for UI display:
      "youtube", "podcast", "blog", "document", "video", "web", "playbook"
      Defaults to source_type when not provided.
    anonymise — run Claude de-identification on the full document before chunking.
      Use for client playbooks and any content containing PII or identifying information.
    """
    if not force and _is_already_ingested(source_url, layer):
        print(f"  Already ingested, skipping: {source_url}")
        return 0

    if not text or not text.strip():
        print(f"  Empty text, skipping: {source_url}")
        return 0

    if anonymise:
        print(f"  Anonymising document ({len(text.split())} words)...")
        text = _anonymise_document(text)
        print(f"  Anonymisation complete.")

    sid = _source_id(source_url)
    raw_chunks = _chunk_text(text)

    if not raw_chunks:
        print(f"  No chunks produced, skipping: {source_url}")
        return 0

    print(f"  Chunking: {len(raw_chunks)} chunks from {len(text.split())} words")

    if normalise:
        print(f"  Normalising {len(raw_chunks)} chunks...")
        raw_chunks = [_normalise_chunk(c) for c in raw_chunks]

    effective_category = content_category or source_type
    now = datetime.now(timezone.utc).isoformat()

    # Text chunks
    all_texts = list(raw_chunks)
    chunk_meta = [
        {
            "id": str(uuid.uuid4()),
            "source_id": sid,
            "source_url": source_url,
            "source_type": source_type,
            "content_category": effective_category,
            "layer": layer,
            "title": title,
            "author": author,
            "date": date,
            "chunk_index": i,
            "total_chunks": len(raw_chunks),
            "text": chunk,
            "embedding_model": EMBEDDING_MODEL,
            "ingested_at": now,
        }
        for i, chunk in enumerate(raw_chunks)
    ]

    # Image description chunks (one per extracted image, brand-relevant only)
    if image_descriptions:
        relevant = []
        for img_data in image_descriptions:
            if isinstance(img_data, dict):
                if img_data.get("brand_relevant", True):  # default True for legacy str
                    relevant.append(img_data)
                else:
                    print(f"    Skipping non-brand-relevant image ({img_data.get('visual_type','?')})")
            else:
                relevant.append(img_data)  # legacy plain string — keep

        if relevant:
            print(f"  Adding {len(relevant)} brand-relevant image description(s)...")
        for img_idx, img_data in enumerate(relevant):
            if isinstance(img_data, dict):
                desc = img_data.get("description", "")
                img_meta = {k: v for k, v in img_data.items() if k != "description"}
            else:
                desc = img_data
                img_meta = {}
            all_texts.append(desc)
            payload = {
                "id": str(uuid.uuid4()),
                "source_id": sid,
                "source_url": source_url,
                "source_type": "image_description",
                "content_category": effective_category,
                "layer": layer,
                "title": title,
                "chunk_index": len(raw_chunks) + img_idx,
                "total_chunks": len(raw_chunks) + len(relevant),
                "text": desc,
                "embedding_model": EMBEDDING_MODEL,
                "ingested_at": now,
            }
            payload.update(img_meta)
            chunk_meta.append(payload)
        image_descriptions = relevant  # update count for final print

    # Embed all chunks (text + image descriptions) in batches of 100
    batch_size = 100
    all_embeddings = []
    for i in tqdm(range(0, len(all_texts), batch_size), desc="  Embedding", leave=False):
        batch = all_texts[i : i + batch_size]
        all_embeddings.extend(_embed(batch))

    # Upsert into Qdrant with retries
    points = [
        PointStruct(
            id=chunk["id"],
            vector=embedding,
            payload={k: v for k, v in chunk.items() if k != "id"},
        )
        for chunk, embedding in zip(chunk_meta, all_embeddings)
    ]
    _upsert_with_retry(points)

    # Save text chunks to disk (image descriptions are ephemeral — re-generated if needed)
    _save_chunks([c for c in chunk_meta if c["source_type"] != "image_description"])

    print(f"  Ingested {len(chunk_meta)} chunks ({len(raw_chunks)} text + {len(image_descriptions or [])} images) → Qdrant + disk")
    return len(chunk_meta)


def reembed(embedding_model: str = EMBEDDING_MODEL) -> None:
    """Re-embed all chunks from disk using a new embedding model. Use when switching models."""
    all_dirs = [_chunks_dir("layer1"), _chunks_dir("layer2")]
    chunk_files = [f for d in all_dirs for f in d.glob("*_chunks.json")]
    if not chunk_files:
        print("No chunk files found in layer1/chunks or layer2/chunks")
        return

    print(f"Re-embedding {len(chunk_files)} sources with {embedding_model}...")
    client = _get_qdrant()

    for chunk_file in tqdm(chunk_files, desc="Sources"):
        with open(chunk_file) as f:
            chunks_data = json.load(f)

        texts = [c["text"] for c in chunks_data]
        all_embeddings = []
        for i in range(0, len(texts), 100):
            batch = texts[i : i + 100]
            response = _get_openai().embeddings.create(model=embedding_model, input=batch)
            all_embeddings.extend([item.embedding for item in response.data])

        points = [
            PointStruct(
                id=chunk["id"],
                vector=embedding,
                payload={k: v for k, v in chunk.items() if k != "id"},
            )
            for chunk, embedding in zip(chunks_data, all_embeddings)
        ]
        _upsert_with_retry(points)

    print("Re-embedding complete.")
