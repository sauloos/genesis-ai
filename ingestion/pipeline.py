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
from openai import OpenAI
from qdrant_client import QdrantClient
from qdrant_client.models import Distance, PointStruct, VectorParams
from tqdm import tqdm

load_dotenv()

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


def _chunks_dir(layer: str) -> Path:
    return _KNOWLEDGE_DIR / layer / "chunks"


def _get_openai() -> OpenAI:
    global _openai
    if _openai is None:
        _openai = OpenAI(api_key=os.getenv("OPENAI_API_KEY"))
    return _openai


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


def _upsert_with_retry(points, max_attempts: int = 3) -> None:
    """POST points directly via REST — avoids httpx connection-pool staleness."""
    import json as _json
    import time
    import requests

    url = QDRANT_URL.rstrip("/") + f"/collections/{COLLECTION_NAME}/points"
    headers = {"Content-Type": "application/json"}
    if QDRANT_API_KEY:
        headers["api-key"] = QDRANT_API_KEY

    payload = {
        "points": [
            {"id": p.id, "vector": p.vector, "payload": p.payload}
            for p in points
        ]
    }

    for attempt in range(1, max_attempts + 1):
        try:
            resp = requests.put(url, headers=headers, data=_json.dumps(payload), timeout=30)
            resp.raise_for_status()
            return
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
) -> int:
    """
    Process extracted text through the full pipeline.
    Returns the number of chunks ingested (0 if skipped as duplicate).
    """
    if not force and _is_already_ingested(source_url, layer):
        print(f"  Already ingested, skipping: {source_url}")
        return 0

    if not text or not text.strip():
        print(f"  Empty text, skipping: {source_url}")
        return 0

    sid = _source_id(source_url)
    raw_chunks = _chunk_text(text)

    if not raw_chunks:
        print(f"  No chunks produced, skipping: {source_url}")
        return 0

    print(f"  Chunking: {len(raw_chunks)} chunks from {len(text.split())} words")

    if normalise:
        print(f"  Normalising {len(raw_chunks)} chunks...")
        raw_chunks = [_normalise_chunk(c) for c in raw_chunks]

    chunks_data = [
        {
            "id": str(uuid.uuid4()),
            "source_id": sid,
            "source_url": source_url,
            "source_type": source_type,
            "layer": layer,
            "title": title,
            "author": author,
            "date": date,
            "chunk_index": i,
            "total_chunks": len(raw_chunks),
            "text": chunk,
            "embedding_model": EMBEDDING_MODEL,
            "ingested_at": datetime.now(timezone.utc).isoformat(),
        }
        for i, chunk in enumerate(raw_chunks)
    ]

    # Embed in batches of 100
    batch_size = 100
    all_embeddings = []
    for i in tqdm(range(0, len(raw_chunks), batch_size), desc="  Embedding", leave=False):
        batch = raw_chunks[i : i + batch_size]
        all_embeddings.extend(_embed(batch))

    # Upsert into Qdrant with retries (cloud connections can drop)
    points = [
        PointStruct(
            id=chunk["id"],
            vector=embedding,
            payload={k: v for k, v in chunk.items() if k != "id"},
        )
        for chunk, embedding in zip(chunks_data, all_embeddings)
    ]
    _upsert_with_retry(points)

    # Save chunks to disk (backup / re-embed source of truth)
    _save_chunks(chunks_data)

    print(f"  Ingested {len(chunks_data)} chunks → Qdrant + disk")
    return len(chunks_data)


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
