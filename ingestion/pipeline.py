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
        kwargs = {"url": QDRANT_URL}
        if QDRANT_API_KEY:
            kwargs["api_key"] = QDRANT_API_KEY
        _qdrant = QdrantClient(**kwargs)
        _ensure_collection(_qdrant)
    return _qdrant


def _ensure_collection(client: QdrantClient) -> None:
    existing = [c.name for c in client.get_collections().collections]
    if COLLECTION_NAME not in existing:
        client.create_collection(
            collection_name=COLLECTION_NAME,
            vectors_config=VectorParams(size=EMBEDDING_DIM, distance=Distance.COSINE),
        )
        print(f"Created Qdrant collection: {COLLECTION_NAME}")


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

    # Upsert into Qdrant
    client = _get_qdrant()
    points = [
        PointStruct(
            id=chunk["id"],
            vector=embedding,
            payload={k: v for k, v in chunk.items() if k != "id"},
        )
        for chunk, embedding in zip(chunks_data, all_embeddings)
    ]
    client.upsert(collection_name=COLLECTION_NAME, points=points)

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
        client.upsert(collection_name=COLLECTION_NAME, points=points)

    print("Re-embedding complete.")
